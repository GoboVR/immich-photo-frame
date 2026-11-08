# Photo Frame

An Android digital photo frame powered by **Immich**, with **Home Assistant Assist**
(tap-to-talk + on-device wake word), **liquid-glass widgets**, and a **custom HA
integration** that turns the frame into a speaker and lets HA control it.

```
app/                         Android app (Kotlin, Jetpack Compose, minSdk 31)
custom_components/photo_frame  Home Assistant integration (HACS-compatible)
docs/PROTOCOL.md             Frame ⇄ HA wire protocol
tests/                       fake_frame.py, fake_immich.py, HA integration tests
```

## Features

**On the frame**
- Immich slideshow: albums, people, "on this day" memories, random, or a weighted mix of all of them
- Ken Burns slow zoom + crossfades; portrait photos sit on a blurred fill instead of being cropped
- Liquid-glass widgets (AGSL refraction, chromatic rim, specular highlight; plain blur on Android 12):
  clock, weather + 4-day forecast, photo location/date/"5 years ago", quick HA controls on tap,
  now playing (music on the frame, or any HA speaker you pick — e.g. YT Music on a Nest) with play/pause/skip
- Assist: mic button or wake word ("Hey Jarvis", "Alexa", "Hey Mycroft", "Ok Nabu"), all on-device
  via openWakeWord/ONNX; speech goes through your HA Assist pipeline, replies are spoken
- Swipe left/right for next/previous photo, night mode (dim drifting clock), auto brightness
- Kiosk: can be the home app, starts on boot, back button does nothing
- Root (Magisk): real screen power on/off, auto-grants permissions, battery-optimisation whitelist

**In Home Assistant**
- Zero-typing setup: auto-discovered via zeroconf → enter the 6-digit code shown on the frame →
  the integration creates a "Photo Frame" HA user + token and hands it to the frame
- `media_player`: TTS target, announcements (ducks music), plays music, **videos, images and
  camera streams full screen** (pick them in the media browser, or `extra: {duration: 30}` in automations)
- `light.<frame>_screen` (on/off + brightness), switches (slideshow, night mode, auto brightness, wake word),
  buttons (next/prev photo, start Assist, reload), selects (source, album, person, wake word),
  number (interval), sensors (battery, light, Wi-Fi, assist state, photo date), image (current photo)
- Services: `photo_frame.show_message`, `photo_frame.show_asset`, `photo_frame.set_source`
- Events: `photo_frame_event` (touch, assist_wake, assist_result, photo_changed, media_ended)

## Setup

### 1. Tablet
1. Flash LineageOS (Android 12+), install Magisk.
2. Settings → Security → Screen lock: **None**. Developer options → **Stay awake** while charging.
3. Install the APK: `adb install -r app/build/outputs/apk/release/app-release.apk`
4. Open the app → grant microphone → when Magisk asks, **grant root (Remember forever)**.
5. Long-press the clock (or tap the screen → ⚙) to open settings.
6. Settings → Device → **Set as home app** → Photo Frame.

### 2. Immich
1. In Immich: Account settings → API keys → New API key (permissions: `asset.read`, `asset.view`,
   `album.read`, `person.read`, `memory.read`, `server.about`, or just "all").
2. Frame settings → Immich → paste the URL (e.g. `http://192.168.1.10:2283`) and key → **Test connection**.
3. Pick a source. "Everything" mixes memories, your selected albums, selected people and random photos.

### 3. Home Assistant
1. Copy `custom_components/photo_frame` to `<config>/custom_components/` (or add this repo to HACS
   as a custom repository) and restart HA.
2. HA should show **Discovered: Photo Frame**. Otherwise: Settings → Devices & services →
   Add integration → Photo Frame → enter the tablet's IP (shown in frame settings), port 8765.
3. Enter the 6-digit code shown on the frame. Leave "Give the frame access to Home Assistant" on.
4. Integration → Configure: pick a weather entity and up to 5 quick-control entities.

Assist uses your **preferred pipeline** (needs STT + TTS, e.g. Whisper/Piper or HA Cloud);
choose another one in frame settings → Home Assistant → Load pipelines.

### Example automations

```yaml
# Doorbell → announce + show on the frame
- trigger: [{ trigger: state, entity_id: binary_sensor.doorbell, to: "on" }]
  action:
    - action: tts.speak
      target: { entity_id: tts.piper }
      data: { media_player_entity_id: media_player.moms_frame, message: "Someone is at the door" }
    - action: photo_frame.show_message
      target: { entity_id: media_player.moms_frame }
      data: { title: "Doorbell", message: "Someone is at the front door", icon: "mdi:doorbell" }

# Doorbell → show the camera full screen for 30 s (wakes the screen at night too)
- trigger: [{ trigger: state, entity_id: binary_sensor.doorbell, to: "on" }]
  action:
    - action: media_player.play_media
      target: { entity_id: media_player.moms_frame }
      data:
        media_content_id: media-source://camera/camera.front_door
        media_content_type: video
        extra: { title: "Front door", duration: 30 }

# Screen off at night, on in the morning
- trigger: [{ trigger: time, at: "23:00:00" }]
  action: [{ action: light.turn_off, target: { entity_id: light.moms_frame_screen } }]
- trigger: [{ trigger: time, at: "07:00:00" }]
  action: [{ action: light.turn_on, target: { entity_id: light.moms_frame_screen } }]
```

## Building

Requirements: JDK 17, Android SDK 35.

```bash
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk (debug-signed)
```

## Testing without hardware

```bash
python3 tests/fake_immich.py              # fake Immich with generated photos on :2283
python3 tests/fake_frame.py --code 123456 # fake frame for developing the HA integration
```

## Third-party models

The wake word models in `app/src/main/assets/wakeword/` come from
[openWakeWord](https://github.com/dscripka/openWakeWord) (melspectrogram, embedding,
"hey jarvis", "alexa", "hey mycroft"; pre-trained models licensed **CC BY-NC-SA 4.0**, i.e.
non-commercial use) and the
[Home Assistant wake words collection](https://github.com/fwartner/home-assistant-wakewords-collection) ("ok nabu").
