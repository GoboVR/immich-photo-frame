#!/usr/bin/env python3
"""Minimal fake Home Assistant websocket API for testing the frame app.

Supports: auth, supported_features, subscribe_entities (weather.home + light.lamp),
call_service (weather.get_forecasts, homeassistant.toggle), unsubscribe_events.
Usage: python3 tests/fake_ha.py [--port 8123] [--token secret]
"""
import argparse
import io
import json
from datetime import datetime, timezone

from aiohttp import WSMsgType, web

STATES = {
    "weather.home": {"s": "partlycloudy", "a": {"friendly_name": "Home", "temperature": 21.4, "temperature_unit": "°C"}},
    "light.lamp": {"s": "off", "a": {"friendly_name": "Living room lamp"}},
    "media_player.kitchen": {"s": "playing", "a": {
        "friendly_name": "Kitchen speaker", "app_name": "YouTube Music",
        "media_title": "Here Comes the Sun", "media_artist": "The Beatles",
        "entity_picture": "/api/media_player_proxy/media_player.kitchen?token=abc&cache=1",
        "media_duration": 185, "media_position": 42,
        "media_position_updated_at": datetime.now(timezone.utc).isoformat(),
        "supported_features": 1 | 16 | 32 | 16384,
    }},
}
FORECAST = [
    {"datetime": f"2026-11-{8 + i:02d}T12:00:00+00:00", "condition": c, "temperature": t, "templow": t - 8}
    for i, (c, t) in enumerate([("partlycloudy", 22), ("sunny", 24), ("rainy", 17), ("cloudy", 19), ("sunny", 23)])
]


async def ws_handler(request):
    token = request.app["token"]
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    print("ws: client connected", flush=True)
    await ws.send_json({"type": "auth_required", "ha_version": "2026.11.0"})
    subs = {}
    async for msg in ws:
        if msg.type != WSMsgType.TEXT:
            continue
        m = json.loads(msg.data)
        t = m.get("type")
        if t == "auth":
            ok = m.get("access_token") == token
            await ws.send_json({"type": "auth_ok" if ok else "auth_invalid"})
            print(f"ws: auth {'ok' if ok else 'INVALID'}", flush=True)
            if not ok:
                await ws.close()
            continue
        mid = m.get("id")
        if t == "subscribe_entities":
            subs[mid] = m["entity_ids"]
            await ws.send_json({"id": mid, "type": "result", "success": True, "result": None})
            await ws.send_json({"id": mid, "type": "event", "event": {"a": {e: STATES[e] for e in m["entity_ids"] if e in STATES}}})
            print(f"ws: subscribe_entities {m['entity_ids']}", flush=True)
        elif t == "call_service":
            print(f"ws: call_service {m['domain']}.{m['service']} {m.get('target')}", flush=True)
            result = None
            if m["domain"] == "weather" and m["service"] == "get_forecasts":
                eid = m["target"]["entity_id"]
                result = {"context": {}, "response": {eid: {"forecast": FORECAST}}}
            elif m["service"] in ("media_play_pause", "media_next_track"):
                eid = m["target"]["entity_id"]
                a = STATES[eid]["a"]
                if m["service"] == "media_next_track":
                    a.update(media_title="Something", media_position=0)
                else:
                    STATES[eid]["s"] = "paused" if STATES[eid]["s"] == "playing" else "playing"
                a["media_position_updated_at"] = datetime.now(timezone.utc).isoformat()
                for sid, ids in subs.items():
                    if eid in ids:
                        await ws.send_json({"id": sid, "type": "event", "event": {"c": {eid: {"+": {"s": STATES[eid]["s"], "a": a}}}}})
            elif m["service"] == "toggle":
                eid = m["target"]["entity_id"]
                STATES[eid]["s"] = "off" if STATES[eid]["s"] == "on" else "on"
                for sid, ids in subs.items():
                    if eid in ids:
                        await ws.send_json({"id": sid, "type": "event", "event": {"c": {eid: {"+": {"s": STATES[eid]["s"]}}}}})
            await ws.send_json({"id": mid, "type": "result", "success": True, "result": result})
        else:
            await ws.send_json({"id": mid, "type": "result", "success": True, "result": None})
    print("ws: client disconnected", flush=True)
    return ws


async def art_handler(request):
    from PIL import Image, ImageDraw
    img = Image.new("RGB", (300, 300), (250, 190, 60))
    d = ImageDraw.Draw(img)
    d.ellipse([60, 60, 240, 240], fill=(255, 235, 140))
    buf = io.BytesIO()
    img.save(buf, "JPEG")
    return web.Response(body=buf.getvalue(), content_type="image/jpeg")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8123)
    ap.add_argument("--token", default="secret")
    a = ap.parse_args()
    app = web.Application()
    app["token"] = a.token
    app.router.add_get("/api/websocket", ws_handler)
    app.router.add_get("/api/media_player_proxy/{entity_id}", art_handler)
    print(f"fake HA on :{a.port}", flush=True)
    web.run_app(app, port=a.port, print=None)


if __name__ == "__main__":
    main()
