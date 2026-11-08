#!/usr/bin/env python3
"""Tiny fake Immich server for testing the frame without a real library.

Serves the endpoints the app uses with procedurally generated "photos".
Usage: python3 tests/fake_immich.py [--port 2283]   (API key: anything)
Requires Pillow.
"""
import argparse
import io
import json
import math
import random
import re
from datetime import date
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from PIL import Image, ImageDraw, ImageFilter

CITIES = [("Austin", "Texas", "United States"), ("Lisbon", None, "Portugal"), ("Kyoto", None, "Japan"),
          ("Banff", "Alberta", "Canada"), ("Cape Town", None, "South Africa"), (None, None, None)]
PALETTES = [((255, 154, 98), (120, 60, 140)), ((90, 170, 230), (20, 50, 90)), ((250, 210, 120), (230, 90, 70)),
            ((120, 200, 160), (20, 70, 80)), ((240, 170, 200), (80, 70, 160)), ((60, 80, 120), (10, 10, 30))]

ASSETS = []
for i in range(24):
    city = CITIES[i % len(CITIES)]
    portrait = i % 5 == 3
    ASSETS.append({
        "id": f"00000000-0000-0000-0000-{i:012d}", "type": "IMAGE", "isTrashed": False,
        "localDateTime": f"{2012 + i % 12}-{1 + i % 12:02d}-{1 + i % 27:02d}T15:30:00.000Z",
        "exifInfo": {"city": city[0], "state": city[1], "country": city[2], "description": "Birthday dinner" if i == 4 else "",
                     "exifImageWidth": 1200 if portrait else 1920, "exifImageHeight": 1800 if portrait else 1200,
                     "dateTimeOriginal": f"{2012 + i % 12}-{1 + i % 12:02d}-{1 + i % 27:02d}T15:30:00.000Z"},
        "people": [{"name": "Anna"}] if i % 3 == 0 else [],
        "_portrait": portrait, "_seed": i,
    })
ALBUM = {"id": "album-1", "albumName": "Mom's Frame", "assetCount": 12}
PEOPLE = [{"id": "person-1", "name": "Anna"}, {"id": "person-2", "name": ""}]
_cache = {}


def render(asset):
    if asset["id"] in _cache:
        return _cache[asset["id"]]
    rnd = random.Random(asset["_seed"])
    w, h = (1200, 1800) if asset["_portrait"] else (1920, 1200)
    top, bottom = PALETTES[asset["_seed"] % len(PALETTES)]
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(top[k] * (1 - t) + bottom[k] * t) for k in range(3)))
    sx, sy, r = rnd.randint(w // 5, 4 * w // 5), rnd.randint(h // 8, h // 3), rnd.randint(60, 140)
    d.ellipse([sx - r, sy - r, sx + r, sy + r], fill=(255, 240, 200))
    for layer in range(3):
        shade = tuple(max(0, c - 40 - layer * 35) for c in bottom)
        base = h * (0.55 + layer * 0.13)
        pts = [(0, h)]
        for x in range(0, w + 40, 40):
            pts.append((x, base + math.sin(x / (180 + layer * 70) + rnd.random() * 6) * (70 - layer * 15)))
        pts.append((w, h))
        d.polygon(pts, fill=shade)
    img = img.filter(ImageFilter.GaussianBlur(1))
    buf = io.BytesIO()
    img.save(buf, "JPEG", quality=88)
    _cache[asset["id"]] = buf.getvalue()
    return _cache[asset["id"]]


def public(a):
    return {k: v for k, v in a.items() if not k.startswith("_")}


class Handler(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _auth(self):
        if not self.headers.get("x-api-key"):
            self._json({"message": "Authentication required"}, 401)
            return False
        return True

    def do_GET(self):
        if not self._auth():
            return
        path = self.path.split("?")[0]
        if path == "/api/server/about":
            return self._json({"version": "v2.2.0-fake"})
        if path == "/api/albums":
            return self._json([] if "shared=true" in self.path else [ALBUM])
        if path == "/api/people":
            return self._json({"people": PEOPLE, "total": len(PEOPLE)})
        if path == "/api/memories":
            today = date.today()
            return self._json([{"type": "on_this_day", "data": {"year": today.year - 5},
                                "assets": [public(a) for a in ASSETS[:3]]}])
        m = re.match(r"^/api/assets/([^/]+)/thumbnail$", path)
        if m:
            a = next((a for a in ASSETS if a["id"] == m.group(1)), None)
            if not a:
                return self._json({"message": "not found"}, 404)
            body = render(a)
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        m = re.match(r"^/api/assets/([^/]+)$", path)
        if m:
            a = next((a for a in ASSETS if a["id"] == m.group(1)), None)
            return self._json(public(a)) if a else self._json({"message": "not found"}, 404)
        self._json({"message": "not found"}, 404)

    def do_POST(self):
        if not self._auth():
            return
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        if self.path == "/api/search/random":
            return self._json([public(a) for a in random.sample(ASSETS, min(len(ASSETS), body.get("size", 20)))])
        if self.path == "/api/search/metadata":
            items = ASSETS
            if body.get("albumIds"):
                items = ASSETS[:12]
            if body.get("personIds"):
                items = [a for a in ASSETS if a["people"]]
            return self._json({"assets": {"items": [public(a) for a in items], "nextPage": None, "total": len(items)}})
        self._json({"message": "not found"}, 404)

    def log_message(self, fmt, *args):
        print("immich:", fmt % args)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=2283)
    args = ap.parse_args()
    print(f"fake immich on :{args.port}")
    ThreadingHTTPServer(("0.0.0.0", args.port), Handler).serve_forever()
