"""Fake Photo Frame server implementing docs/PROTOCOL.md (v1).

Run:  python3 tests/fake_frame.py [--port 8765] [--code 123456] [--zeroconf]

- Pairing code is printed to stdout (fixed with --code for scripted tests).
- Commands mutate an in-memory state and push it to all WebSocket clients.
- --zeroconf advertises _photoframe._tcp.local. (needs the `zeroconf` package).
- Type an event name on stdin (e.g. `touch`, `assist_wake`, `photo_changed`)
  to push that event to connected clients.
"""

from __future__ import annotations

import argparse
import asyncio
import copy
from datetime import UTC, datetime
import io
import json
import logging
import secrets
import socket
import struct
import sys
import uuid
import zlib

from aiohttp import WSMsgType, web

_LOGGER = logging.getLogger("fake_frame")

FRAME_ID = str(uuid.uuid5(uuid.NAMESPACE_DNS, "fake-photo-frame"))


def _now() -> str:
    return datetime.now(UTC).isoformat().replace("+00:00", "Z")


def initial_state(name: str) -> dict:
    return {
        "id": FRAME_ID,
        "name": name,
        "screen": {"on": True, "brightness": 180, "auto_brightness": False, "night_mode": False},
        "slideshow": {
            "playing": True,
            "interval": 30,
            "source": "mixed",
            "album_ids": ["alb-1"],
            "person_ids": [],
            "current": {
                "asset_id": "asset-1",
                "taken_at": "2019-07-04T18:22:00Z",
                "city": "Austin",
                "country": "United States",
                "description": "",
                "people": ["Anna"],
            },
        },
        "sources": {
            "albums": [
                {"id": "alb-1", "name": "Family", "count": 214},
                {"id": "alb-2", "name": "Vacation", "count": 87},
                {"id": "alb-3", "name": "Family", "count": 3},
            ],
            "people": [{"id": "p-1", "name": "Anna"}, {"id": "p-2", "name": "Ben"}],
        },
        "media": {
            "state": "idle",
            "volume": 0.6,
            "muted": False,
            "url": None,
            "title": None,
            "artist": None,
            "album_art": None,
            "duration": None,
            "position": None,
            "position_updated_at": None,
        },
        "assist": {
            "state": "idle",
            "wake_word_enabled": True,
            "wake_word": "hey_jarvis",
            "available_wake_words": ["hey_jarvis", "alexa", "hey_mycroft", "ok_nabu"],
        },
        "device": {
            "battery": 87,
            "charging": True,
            "light_lux": 120.5,
            "wifi_rssi": -55,
            "uptime": 3600,
            "rooted": False,
        },
        "widgets": {
            "clock": True,
            "weather": True,
            "photo_info": True,
            "controls": True,
            "weather_entity": None,
            "entities": [],
        },
        "ha": {"connected": False, "url": None},
    }


def tiny_jpeg_placeholder(seed: int) -> bytes:
    """Return a small PNG (served as image bytes; content is irrelevant for tests)."""

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(
            ">I", zlib.crc32(tag + data) & 0xFFFFFFFF
        )

    w = h = 8
    color = bytes(((seed * 50) % 256, (seed * 90) % 256, (seed * 130) % 256))
    raw = b"".join(b"\x00" + color * w for _ in range(h))
    buf = io.BytesIO()
    buf.write(b"\x89PNG\r\n\x1a\n")
    buf.write(chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)))
    buf.write(chunk(b"IDAT", zlib.compress(raw)))
    buf.write(chunk(b"IEND", b""))
    return buf.getvalue()


class FakeFrame:
    def __init__(self, name: str, fixed_code: str | None) -> None:
        self.name = name
        self.fixed_code = fixed_code
        self.state = initial_state(name)
        self.tokens: set[str] = set()
        self.code: str | None = None
        self.code_expires = 0.0
        self.clients: set[web.WebSocketResponse] = set()
        self.commands: list[tuple[str, dict]] = []  # log, for tests
        self._asset_n = 1
        self.started = asyncio.get_event_loop().time()
        self.media_length = 5.0
        self._media_timer: asyncio.TimerHandle | None = None

    def cancel_timers(self) -> None:
        if self._media_timer is not None:
            self._media_timer.cancel()
            self._media_timer = None

    # ---------------------------------------------------------------- helpers

    def authorized(self, request: web.Request) -> bool:
        auth = request.headers.get("Authorization", "")
        token = auth.removeprefix("Bearer ").strip() if auth.startswith("Bearer ") else None
        token = token or request.query.get("token")
        return token in self.tokens

    def snapshot(self) -> dict:
        s = copy.deepcopy(self.state)
        s["device"]["uptime"] = 3600 + int(asyncio.get_event_loop().time() - self.started)
        return s

    async def broadcast(self, msg: dict) -> None:
        for ws in list(self.clients):
            try:
                await ws.send_json(msg)
            except ConnectionError:
                self.clients.discard(ws)

    async def push_state(self) -> None:
        await self.broadcast({"type": "state", "data": self.snapshot()})

    async def event(self, name: str, data: dict | None = None) -> None:
        await self.broadcast({"type": "event", "event": name, "data": data or {}})

    async def next_photo(self, delta: int = 1) -> None:
        self._asset_n = max(1, self._asset_n + delta)
        aid = f"asset-{self._asset_n}"
        self.state["slideshow"]["current"]["asset_id"] = aid
        await self.event("photo_changed", {"asset_id": aid})

    # --------------------------------------------------------------- commands

    async def run_command(self, command: str, params: dict) -> tuple[bool, str | None, object]:
        _LOGGER.info("command %s %s", command, params)
        self.commands.append((command, params))
        s = self.state
        match command:
            case "screen_on":
                s["screen"]["on"] = True
            case "screen_off":
                s["screen"]["on"] = False
            case "set_brightness":
                s["screen"]["brightness"] = int(params["brightness"])
            case "set_auto_brightness":
                s["screen"]["auto_brightness"] = bool(params["enabled"])
            case "set_night_mode":
                s["screen"]["night_mode"] = bool(params["enabled"])
            case "slideshow_next":
                await self.next_photo(1)
            case "slideshow_previous":
                await self.next_photo(-1)
            case "slideshow_play":
                s["slideshow"]["playing"] = True
            case "slideshow_pause":
                s["slideshow"]["playing"] = False
            case "set_interval":
                sec = int(params["seconds"])
                if not 5 <= sec <= 3600:
                    return False, "interval out of range", None
                s["slideshow"]["interval"] = sec
            case "set_source":
                s["slideshow"]["source"] = params["source"]
                if "album_ids" in params:
                    s["slideshow"]["album_ids"] = params["album_ids"]
                if "person_ids" in params:
                    s["slideshow"]["person_ids"] = params["person_ids"]
            case "show_asset":
                s["slideshow"]["current"]["asset_id"] = params["asset_id"]
                await self.event("photo_changed", {"asset_id": params["asset_id"]})
            case "reload_sources":
                pass
            case "media_play":
                s["media"].update(
                    state="playing",
                    url=params["url"],
                    title=params.get("title"),
                    artist=params.get("artist"),
                    album_art=params.get("album_art"),
                    duration=5.0,
                    position=0.0,
                    position_updated_at=_now(),
                )
                self.cancel_timers()
                self._media_timer = asyncio.get_event_loop().call_later(
                    self.media_length, lambda: asyncio.ensure_future(self._media_end())
                )
            case "media_pause":
                s["media"]["state"] = "paused"
            case "media_resume":
                s["media"]["state"] = "playing"
            case "media_stop":
                await self._media_end(push=False)
            case "media_seek":
                s["media"]["position"] = float(params["position"])
                s["media"]["position_updated_at"] = _now()
            case "set_volume":
                s["media"]["volume"] = float(params["volume"])
            case "set_mute":
                s["media"]["muted"] = bool(params["muted"])
            case "show_message":
                print(f"[toast] {params.get('title') or ''}: {params['message']}", flush=True)
            case "assist_start":
                s["assist"]["state"] = "listening"
                await self.event("assist_wake")
            case "set_wake_word":
                s["assist"]["wake_word_enabled"] = bool(params["enabled"])
                if params.get("wake_word"):
                    s["assist"]["wake_word"] = params["wake_word"]
            case "set_widgets":
                s["widgets"].update(params)
            case "provision_ha":
                s["ha"] = {"connected": True, "url": params["url"]}
                print(f"[provision_ha] url={params['url']} token={params['token'][:12]}...", flush=True)
            case _:
                return False, f"unknown command: {command}", None
        await self.push_state()
        return True, None, None

    async def _media_end(self, push: bool = True) -> None:
        if self.state["media"]["state"] == "idle":
            return
        self.state["media"].update(
            state="idle", url=None, title=None, artist=None, album_art=None,
            duration=None, position=None, position_updated_at=None,
        )
        await self.event("media_ended")
        if push:
            await self.push_state()

    # --------------------------------------------------------------- handlers

    async def info(self, request: web.Request) -> web.Response:
        return web.json_response(
            {
                "id": FRAME_ID,
                "name": self.name,
                "model": "Fake Tablet",
                "manufacturer": "Fakecorp",
                "app_version": "0.0.1-fake",
                "android_version": "14",
                "protocol": 1,
            }
        )

    async def pair_start(self, request: web.Request) -> web.Response:
        self.code = self.fixed_code or f"{secrets.randbelow(1_000_000):06d}"
        self.code_expires = asyncio.get_event_loop().time() + 120
        print(f"[pair] code: {self.code}", flush=True)
        return web.json_response({"ok": True})

    async def pair_finish(self, request: web.Request) -> web.Response:
        body = await request.json()
        if (
            self.code is None
            or asyncio.get_event_loop().time() > self.code_expires
            or str(body.get("code")) != self.code
        ):
            return web.json_response({"error": "invalid_code"}, status=401)
        self.code = None
        token = secrets.token_hex(32)
        self.tokens.add(token)
        print(f"[pair] paired with {body.get('client_name')!r}", flush=True)
        return web.json_response({"token": token, "id": FRAME_ID})

    async def get_state(self, request: web.Request) -> web.Response:
        if not self.authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        return web.json_response(self.snapshot())

    async def command(self, request: web.Request) -> web.Response:
        if not self.authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        body = await request.json()
        ok, err, result = await self.run_command(body.get("command"), body.get("params") or {})
        return web.json_response({"success": ok, "error": err, "result": result})

    async def current_photo(self, request: web.Request) -> web.Response:
        if not self.authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        if not self.state["slideshow"]["current"]:
            return web.Response(status=404)
        return web.Response(body=tiny_jpeg_placeholder(self._asset_n), content_type="image/jpeg")

    async def ws(self, request: web.Request) -> web.StreamResponse:
        if not self.authorized(request):
            return web.json_response({"error": "unauthorized"}, status=401)
        ws = web.WebSocketResponse()
        await ws.prepare(request)
        self.clients.add(ws)
        await ws.send_json({"type": "state", "data": self.snapshot()})
        try:
            async for msg in ws:
                if msg.type != WSMsgType.TEXT:
                    continue
                data = json.loads(msg.data)
                if data.get("type") == "ping":
                    await ws.send_json({"type": "pong"})
                elif data.get("type") == "command":
                    ok, err, result = await self.run_command(
                        data.get("command"), data.get("params") or {}
                    )
                    await ws.send_json(
                        {"type": "result", "id": data.get("id"), "success": ok,
                         "error": err, "result": result}
                    )
        finally:
            self.clients.discard(ws)
        return ws

    def app(self) -> web.Application:
        app = web.Application()
        app.router.add_get("/api/v1/info", self.info)
        app.router.add_post("/api/v1/pair/start", self.pair_start)
        app.router.add_post("/api/v1/pair/finish", self.pair_finish)
        app.router.add_get("/api/v1/state", self.get_state)
        app.router.add_post("/api/v1/command", self.command)
        app.router.add_get("/api/v1/current_photo.jpg", self.current_photo)
        app.router.add_get("/api/v1/ws", self.ws)
        return app


async def _stdin_events(frame: FakeFrame) -> None:
    loop = asyncio.get_event_loop()
    while True:
        line = await loop.run_in_executor(None, sys.stdin.readline)
        if not line:
            return
        name = line.strip()
        if not name:
            continue
        if name == "photo_changed":
            await frame.next_photo(1)
            await frame.push_state()
        else:
            await frame.event(name)
        print(f"[event] sent {name}", flush=True)


async def _advertise(name: str, port: int):
    from zeroconf import IPVersion, ServiceInfo
    from zeroconf.asyncio import AsyncZeroconf

    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("10.255.255.255", 1))
        ip = s.getsockname()[0]
    info = ServiceInfo(
        "_photoframe._tcp.local.",
        f"{name}._photoframe._tcp.local.",
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={"id": FRAME_ID, "name": name, "version": "1", "model": "Fake Tablet"},
    )
    azc = AsyncZeroconf(ip_version=IPVersion.V4Only)
    await azc.async_register_service(info)
    print(f"[zeroconf] advertised {name} at {ip}:{port}", flush=True)
    return azc


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--name", default="Fake Frame")
    parser.add_argument("--code", default=None, help="fixed pairing code")
    parser.add_argument("--zeroconf", action="store_true")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)

    frame = FakeFrame(args.name, args.code)
    runner = web.AppRunner(frame.app())
    await runner.setup()
    await web.TCPSite(runner, args.host, args.port).start()
    print(f"[fake_frame] listening on {args.host}:{args.port} id={FRAME_ID}", flush=True)
    azc = await _advertise(args.name, args.port) if args.zeroconf else None
    try:
        await asyncio.gather(_stdin_events(frame), asyncio.Event().wait())
    finally:
        if azc:
            await azc.async_close()
        await runner.cleanup()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
