# Frame ⇄ Home Assistant protocol (v1)

The Android app ("Frame") runs a small HTTP + WebSocket server. The HA custom
integration (`custom_components/photo_frame`) is the client.

Separately, the Frame is itself a client of Home Assistant (websocket API with a
long-lived token) for Assist and for widget entity states. That token can be
pushed to the Frame by the integration (`provision_ha` command) so the user
never has to type it on the tablet.

## Discovery

- mDNS / zeroconf service type: `_photoframe._tcp.local.`
- Default port: `8765`
- Service instance name: the frame's display name (e.g. `Mom's Frame`)
- TXT records: `id=<stable uuid>`, `name=<display name>`, `version=1`, `model=<device model>`

## Auth / pairing

All endpoints except `/api/v1/info`, `/api/v1/pair/start` and `/api/v1/pair/finish`
require `Authorization: Bearer <token>` (WebSocket: same header, or `?token=` query).

1. `POST /api/v1/pair/start` → `{"ok": true}`. The Frame shows a 6-digit code full-screen for 2 minutes.
2. `POST /api/v1/pair/finish` body `{"code": "123456", "client_name": "Home Assistant"}`
   → `200 {"token": "<64 hex chars>", "id": "<frame uuid>"}` or `401 {"error": "invalid_code"}`.

Tokens persist on the Frame until the user revokes them in Frame settings.

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/info` | `{"id","name","model","manufacturer","app_version","android_version","protocol":1}` (no auth) |
| GET | `/api/v1/state` | Full state object (below) |
| POST | `/api/v1/command` | Body `{"command": str, "params": {...}}` → `{"success": bool, "error": str\|null, "result": any}` |
| GET | `/api/v1/current_photo.jpg` | JPEG of the photo currently displayed (≤1280px). `404` if none. |
| WS | `/api/v1/ws` | Push channel (below) |

## WebSocket

On connect, server immediately sends `{"type":"state","data":<state>}`, then
sends a new full `state` message whenever anything changes (debounced ≤ 4/sec).

Server events: `{"type":"event","event":"<name>","data":{...}}` where name is one of:
- `assist_wake` (wake word detected), `assist_result` `{"text": str, "response": str}`
- `touch` (screen touched, at most 1/10 s)
- `photo_changed` `{"asset_id": str}`
- `media_ended`

Client may send commands over the socket:
`{"type":"command","id":<int>,"command":"...","params":{...}}`
→ `{"type":"result","id":<int>,"success":bool,"error":str|null,"result":any}`.

Client may send `{"type":"ping"}` → `{"type":"pong"}`.

## State object

```json
{
  "id": "uuid", "name": "Mom's Frame",
  "screen":    {"on": true, "brightness": 180, "auto_brightness": false, "night_mode": false},
  "slideshow": {
    "playing": true, "interval": 30,
    "source": "mixed",               // album | people | memories | random | mixed
    "album_ids": ["..."], "person_ids": ["..."],
    "current": {"asset_id": "...", "taken_at": "2019-07-04T18:22:00Z", "city": "Austin",
                "country": "United States", "description": "", "people": ["Anna"]} // or null
  },
  "sources": {
    "albums": [{"id": "...", "name": "Mom's Frame", "count": 214}],
    "people": [{"id": "...", "name": "Anna"}]
  },
  "media": {
    "state": "idle",                 // idle | playing | paused | buffering
    "volume": 0.6, "muted": false,
    "url": null, "title": null, "artist": null, "album_art": null,
    "duration": null, "position": null, "position_updated_at": null   // seconds / ISO8601
  },
  "assist":  {"state": "idle", "wake_word_enabled": true, "wake_word": "hey_jarvis",
              "available_wake_words": ["hey_jarvis", "alexa", "hey_mycroft", "ok_nabu"]},
  "device":  {"battery": 87, "charging": true, "light_lux": 120.5, "wifi_rssi": -55,
              "uptime": 3600, "rooted": true},
  "widgets": {"clock": true, "weather": true, "photo_info": true, "controls": true,
              "weather_entity": "weather.home", "entities": ["light.living_room"],
              "now_playing": true, "now_playing_entities": ["media_player.kitchen"]},
  "ha":      {"connected": true, "url": "http://homeassistant.local:8123"}
}
```

`state` values may be `null` when unknown (e.g. no light sensor).

## Commands

| command | params | notes |
|---|---|---|
| `screen_on` / `screen_off` | – | root: real display power; non-root: black overlay |
| `set_brightness` | `brightness` 0–255 | |
| `set_auto_brightness` | `enabled` bool | |
| `set_night_mode` | `enabled` bool | dims + clock-only |
| `slideshow_next` / `slideshow_previous` | – | |
| `slideshow_play` / `slideshow_pause` | – | |
| `set_interval` | `seconds` int 5–3600 | |
| `set_source` | `source` str, optional `album_ids` [str], `person_ids` [str] | |
| `show_asset` | `asset_id` str | jump to a specific Immich asset |
| `reload_sources` | – | refresh albums/people from Immich |
| `media_play` | `url` str, optional `title`, `artist`, `album_art`, `mime`, `announce` bool | `announce`: duck/pause current media, play, then resume |
| `media_pause` / `media_resume` / `media_stop` | – | |
| `media_seek` | `position` float seconds | |
| `set_volume` | `volume` float 0–1 | |
| `set_mute` | `muted` bool | |
| `show_message` | `message` str, optional `title`, `duration` s (default 10), `icon` (mdi name) | glass toast |
| `assist_start` | – | start listening as if mic tapped |
| `set_wake_word` | `enabled` bool, optional `wake_word` str | |
| `set_widgets` | any subset of the `widgets` object | |
| `provision_ha` | `url` str, `token` str, optional `urls` [str] | Frame stores the token and connects to the first reachable URL in `urls` (falls back to `url`) |

## Clarifications

- `assist.state` values: `idle`, `wake_word` (idle, listening for wake word), `listening`, `processing`, `responding`.
- `show_message.icon` may be `mdi:bell` or `bell`; the frame strips the `mdi:` prefix.
- Unauthorized WebSocket upgrades are rejected with HTTP `401` before the upgrade.
- `set_source` without `album_ids` / `person_ids` keeps the existing lists.
- `media_play.url` is fetched verbatim (signed `authSig` query strings preserved).
- `pair/start` while a code is showing issues a fresh code.
- `current_photo.jpg` reflects `slideshow.current.asset_id`; clients cache per asset id.
- `media_play.url` starting with `/` is an HA-relative signed path; the frame prefixes the HA URL it is connected to.
- `set_widgets.now_playing_entities`: other HA `media_player`s shown in the now-playing widget when playing.
- `media_play` optional `media_type` (HA media type), `duration` (s, auto-stop; images default 30), `refresh` (s, re-fetch an image, for camera snapshots). Videos/images show full screen; `media.content` reports `audio|video|image`.

## Live camera

- `rtsp://<frame>:<rtsp_port>/camera`: live H.264 (RTP, RFC 6184, packetization-mode 1) over
  UDP or TCP-interleaved; up to 30 fps from the hardware encoder, SPS/PPS before every keyframe.
  Auth: `?key=<camera key>`, `?token=<HA token>`, or either as the Basic-auth password. The
  integration uses `rtsp://ha:<token>@<frame>:<rtsp_port>/camera` as the HA `stream_source`.
- `GET /api/v1/camera.jpg`: one fresh JPEG. `503` if the camera is off or unavailable.
- `GET /api/v1/camera.mjpeg`: `multipart/x-mixed-replace; boundary=photoframe` stream of JPEGs.
- `GET /camera?key=…`: HTML viewer page.
- Auth: an HA bearer token (as usual), **or** the read-only camera key as `?key=` (or the
  `X-Camera-Key` header). The camera key works only for these three endpoints.
- The camera runs only while at least one client is connected or a snapshot is in progress,
  and stops 15 s after the last one.
- State: `"camera": {"enabled": bool, "available": bool, "streaming": bool, "viewers": int, "rtsp_port": int}`
  (missing on app versions without camera support).
- Command `set_camera` `{"enabled": bool}`: fails if the tablet has no camera or the permission
  hasn't been granted (granted automatically with root).
