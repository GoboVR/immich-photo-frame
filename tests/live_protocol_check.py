#!/usr/bin/env python3
"""Drive a REAL frame (device/emulator) with the integration's own FrameClient.

    adb forward tcp:8765 tcp:8765
    python3 tests/live_protocol_check.py --host 127.0.0.1 --adb   # reads pairing code from logcat (debug builds)
"""
import argparse
import asyncio
import importlib.util
import json
import pathlib
import subprocess
import sys

import aiohttp

ROOT = pathlib.Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("frame_api", ROOT / "custom_components/photo_frame/api.py")
api = importlib.util.module_from_spec(spec)
sys.modules["frame_api"] = api
spec.loader.exec_module(api)


def code_from_logcat() -> str:
    out = subprocess.run(["adb", "logcat", "-d", "-s", "FrameServer:D"], capture_output=True, text=True).stdout
    codes = [l.rsplit(":", 1)[1].strip() for l in out.splitlines() if "pairing code:" in l]
    if not codes:
        raise SystemExit("no pairing code in logcat")
    return codes[-1]


async def main(a):
    ok = True
    def check(name, cond, extra=""):
        nonlocal ok
        ok &= bool(cond)
        print(("PASS " if cond else "FAIL ") + name + (f"  {extra}" if extra else ""))

    async with aiohttp.ClientSession() as s:
        c = api.FrameClient(s, a.host, a.port)
        info = await c.get_info()
        check("info", info.get("protocol") == 1, json.dumps(info))
        try:
            await c.get_state()
            check("state requires auth", False)
        except api.FrameAuthError:
            check("state requires auth", True)
        await c.pair_start()
        await asyncio.sleep(1)
        try:
            await c.pair_finish("000000" if a.code != "000000" else "111111", "test")
            check("wrong code rejected", False)
        except api.FrameInvalidCodeError:
            check("wrong code rejected", True)
        code = a.code or code_from_logcat()
        res = await c.pair_finish(code, "live-check")
        check("pair ok", len(res.get("token", "")) == 64)
        c.token = res["token"]

        st = await c.get_state()
        for k in ["screen", "slideshow", "sources", "media", "assist", "device", "widgets", "ha"]:
            check(f"state.{k}", k in st)

        events, states = [], []
        c.add_event_listener(lambda e, d: events.append(e))
        c.add_state_listener(lambda st: states.append(st))
        c.start()
        for _ in range(50):
            if c.connected: break
            await asyncio.sleep(0.2)
        check("ws connected", c.connected)

        await c.command("set_interval", {"seconds": 45})
        await asyncio.sleep(1)
        check("set_interval reflected", states and states[-1]["slideshow"]["interval"] == 45)
        await c.command("set_widgets", {"clock": True, "weather_entity": "weather.home"})
        await c.command("show_message", {"title": "Hello", "message": "Happy birthday!", "icon": "mdi:cake-variant", "duration": 8})
        await c.command("slideshow_next")
        await asyncio.sleep(3)
        check("photo_changed event", "photo_changed" in events, str(events))
        cur = states[-1]["slideshow"]["current"] if states else None
        check("current photo in state", cur is not None, json.dumps(cur))
        photo = await c.fetch_current_photo()
        check("current_photo.jpg", photo is not None and photo[:2] == b"\xff\xd8", f"{len(photo or b'')} bytes")
        await c.command("set_volume", {"volume": 0.4})
        await asyncio.sleep(1.5)
        check("volume reflected", states and abs(states[-1]["media"]["volume"] - 0.4) < 0.1, str(states[-1]["media"]["volume"]))
        try:
            await c.command("bogus")
            check("unknown command errors", False)
        except api.FrameCommandError as e:
            check("unknown command errors", True, str(e))
        # bad token on ws → 401
        async with s.get(f"http://{a.host}:{a.port}/api/v1/ws", headers={"Authorization": "Bearer nope"}) as r:
            check("ws bad token 401", r.status == 401, str(r.status))
        await c.stop()
    print("ALL PASS" if ok else "SOME FAILED")
    return 0 if ok else 1


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--code")
    ap.add_argument("--adb", action="store_true")
    sys.exit(asyncio.run(main(ap.parse_args())))
