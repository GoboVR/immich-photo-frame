# Immich Photo Frame

Turn an Android tablet into a digital photo frame that shows your own photos and videos from
[Immich](https://immich.app), with a voice assistant, glassy widgets, and full
[Home Assistant](https://www.home-assistant.io) control. Home Assistant can use it as a speaker,
a screen for camera feeds, and more.

Home Assistant is optional: without it you still get the slideshow, clock and photo info.

> [!NOTE]
> **AI notice:** this project was written with substantial help from an AI coding assistant
> ([Claude](https://claude.ai) by Anthropic, via Claude Code). The code, tests and docs were
> generated and iterated on with the AI and then tested on real hardware by the author. It works
> well for us, but please review it before you rely on it, and report anything odd in
> [Issues](https://github.com/GoboVR/immich-photo-frame/issues).

## Features

**Photos & videos**
- Slideshow from Immich: pick albums, people, "on this day" memories, random photos, or a mix of all of them
- Videos play muted in the slideshow; **tap for sound**
- Slow zoom and smooth crossfades; portrait photos sit on a blurred background instead of being cropped
- Shows where and when a photo was taken ("5 years ago · Kyoto")

**On screen**
- "Liquid glass" widgets that bend and blur the photo behind them: clock, weather with a 4-day
  forecast, photo info, now playing, and quick buttons for your Home Assistant lights and scenes
- Night mode (a dim clock that slowly moves to avoid burn-in), auto brightness from the light sensor
- Swipe for next or previous photo; long-press the clock for settings

**Voice (Home Assistant Assist)**
- Tap the mic or say a wake word: "Hey Jarvis", "Alexa", "Hey Mycroft" or "Ok Nabu".
  Wake word detection runs entirely on the tablet.
- The frame notices when you've stopped talking and speaks Home Assistant's reply

**Home Assistant integration**
- Found automatically on your network; pair it by typing the 6-digit code shown on the frame
- A media player: text-to-speech, announcements, music (with album art), plus **videos, pictures and
  live camera feeds full screen** (great for a doorbell)
- Control the screen, brightness, slideshow, photo source, night mode and wake word; sensors for
  battery, light level and Wi-Fi; the current photo as an image entity
- Show messages on the frame, and trigger automations when someone touches it

**Live camera (optional, off by default)**
- Watch the tablet's front camera in Home Assistant, in a browser, in Frigate, or in VLC and other viewer apps
- Only runs while someone is watching, and the frame always shows "Camera is being viewed" while it's live

## What you need

- **An Android tablet** with Android 12 or newer. Android 13+ gets the full glass effect;
  Android 12 gets a simpler blur. A cheap tablet is fine.
- **An Immich server** reachable from the tablet.
- **Home Assistant 2025.1+** (optional): needed for voice, weather, controls and the speaker
  features. Voice needs an Assist pipeline with speech-to-text and text-to-speech, e.g. Whisper and
  Piper, or Home Assistant Cloud.

### Root is recommended

The frame works on a normal tablet, but it works **best on a rooted one** (e.g.
[Magisk](https://github.com/topjohnwu/Magisk), ideally on a clean ROM like LineageOS). With root,
the frame can:

| | With root | Without root |
|---|---|---|
| Screen off/on from Home Assistant | Really turns the display off | Shows black at minimum brightness |
| Starting after a reboot or power cut | Opens itself automatically | Only if you set it as the home app (see below) |
| Permissions and battery optimisation | Handled automatically | You allow them once by hand |

## Install the app

Download from the [**Releases**](https://github.com/GoboVR/immich-photo-frame/releases) page. There
is one APK per processor type: **almost every tablet needs `arm64-v8a`**; `armeabi-v7a` is only for
old 32-bit devices.

### Option 1: Obtainium (recommended, gets updates)

[Obtainium](https://github.com/ImranR98/Obtainium) installs apps straight from GitHub and keeps
them updated.

1. Install Obtainium on the tablet.
2. Tap **Add app**, paste `https://github.com/GoboVR/immich-photo-frame`, and tap **Add**.
3. Tap **Install**. Obtainium picks the right APK for your tablet. If it asks, choose the one
   ending in `arm64-v8a.apk`.

Obtainium will tell you when a new version is out.

### Option 2: Manual

1. On the tablet, open the [latest release](https://github.com/GoboVR/immich-photo-frame/releases/latest)
   and download `immich-photo-frame-<version>-arm64-v8a.apk`.
2. Open the downloaded file. Android will ask you to allow installs from your browser or file
   manager: allow it, then tap **Install**.

Or from a computer with [adb](https://developer.android.com/tools/adb):
`adb install immich-photo-frame-<version>-arm64-v8a.apk`

To update manually, install the newer APK over the old one. Your settings are kept.

## Set up the frame

1. Open **Photo Frame**. Allow the microphone (for voice) and notifications.
   On a rooted tablet, allow root when Magisk asks and tick "Remember".
2. Open settings by **long-pressing the clock**, or tap the screen and press ⚙.
3. **Immich:** in Immich go to *Account settings → API keys → New API key*. Give it at least
   `asset.read`, `asset.view`, `album.read`, `person.read`, `memory.read` and `server.about` (or
   "All"). Paste your Immich address (e.g. `http://192.168.1.10:2283`) and the key into the frame,
   then tap **Test connection**.
4. **Photos:** choose what to show. "Everything" mixes memories, your chosen albums and people, and
   random photos. Turn videos on or off here, and set the longest video to include.

### Make it a dedicated frame

- **Settings → Device → Set as home app → Photo Frame.** The tablet now boots straight into the
  frame, and the home button returns to it. To get back to Android, use the "Android settings"
  button in the frame's settings.
- In Android settings: set **Screen lock** to *None*, and if your tablet has it, turn on
  **Developer options → Stay awake** (while charging).
- Without root, also set the app's battery usage to *Unrestricted* so Android doesn't close it.

## Connect Home Assistant

1. **Install the integration**, then restart Home Assistant:
   - **HACS:** HACS → ⋮ → *Custom repositories* → add
     `https://github.com/GoboVR/immich-photo-frame` (type: *Integration*) → download **Photo Frame**.
   - **Manually:** copy `custom_components/photo_frame` into your Home Assistant
     `config/custom_components/` folder.
2. Home Assistant should show **Discovered: Photo Frame** under *Settings → Devices & services*.
   If not, choose *Add integration → Photo Frame* and enter the tablet's IP address, shown in the
   frame's settings, with port `8765`.
3. Type in the **6-digit code** shown on the frame. Leave *"Give the frame access to Home
   Assistant"* on: the integration then creates a separate, non-admin Home Assistant user for the
   frame, so there are no tokens to copy around.
4. Under **Configure** on the integration, choose:
   - a weather entity
   - up to 5 quick-control buttons (lights, switches, scenes, …)
   - other speakers to show in the now-playing card, e.g. a Nest speaker you cast YouTube Music to

The frame uses your **preferred** Assist pipeline. To use a different one, go to the frame's
settings → *Home Assistant* → *Load pipelines*.

### Example automations

```yaml
# Doorbell: say who's there and show the camera full screen for 30 seconds
- triggers:
    - trigger: state
      entity_id: binary_sensor.doorbell
      to: "on"
  actions:
    - action: tts.speak
      target: { entity_id: tts.piper }
      data:
        media_player_entity_id: media_player.photo_frame
        message: "Someone is at the front door"
    - action: media_player.play_media
      target: { entity_id: media_player.photo_frame }
      data:
        media_content_id: media-source://camera/camera.front_door
        media_content_type: video
        extra: { title: "Front door", duration: 30 }

# Show a message on the frame
- actions:
    - action: photo_frame.show_message
      target: { entity_id: media_player.photo_frame }
      data: { title: "Dinner", message: "Dinner's ready!", icon: "mdi:silverware-fork-knife" }

# Screen off at night, on in the morning
- triggers: [{ trigger: time, at: "23:00:00" }]
  actions: [{ action: light.turn_off, target: { entity_id: light.photo_frame_screen } }]
- triggers: [{ trigger: time, at: "07:00:00" }]
  actions: [{ action: light.turn_on, target: { entity_id: light.photo_frame_screen } }]
```

Entity names follow your frame's name: rename the frame to "Kitchen Frame" and you get
`media_player.kitchen_frame`, and so on.

## Live camera

Turn it on in the frame's settings → **Live camera** (Android will ask for camera permission), or
with the **Camera** switch in Home Assistant once you've allowed it on the frame.

- **Home Assistant:** a camera entity (e.g. `camera.photo_frame_camera`) appears automatically.
- **Browser:** open the *Viewer page* link shown in the frame's settings:
  `http://<frame-ip>:8765/camera?key=<camera key>`
- **VLC and most "IP camera" apps:** open the *MJPEG stream* link
  (`http://<frame-ip>:8765/api/v1/camera.mjpeg?key=<camera key>`).
- **Frigate:** add the stream to Frigate's built-in go2rtc. go2rtc can also convert it to H.264
  and serve it as **RTSP** for anything that only speaks RTSP:

  ```yaml
  go2rtc:
    streams:
      photo_frame: http://<frame-ip>:8765/api/v1/camera.mjpeg?key=<camera key>
      photo_frame_h264: ffmpeg:photo_frame#video=h264
  cameras:
    photo_frame:
      ffmpeg:
        inputs:
          - path: rtsp://127.0.0.1:8554/photo_frame_h264
            input_args: preset-rtsp-restream
            roles: [detect, record]
  ```
  The RTSP stream is then also available at `rtsp://<frigate-host>:8554/photo_frame_h264`.

**Privacy:** the camera is off until you turn it on, and it only runs while someone is actually
watching (it stops 15 seconds after the last viewer leaves). While it's live, the frame shows a
"Camera is being viewed" label on top of everything, and Android shows its green camera dot. The
camera key can only watch the camera, not control the frame; tap **New key** in settings to cut
off everyone using the old one. If others live where the frame is, tell them about the camera.

## Troubleshooting

- **Frame says "Disconnected" from Home Assistant.** The frame tries several Home Assistant
  addresses automatically; the last error is shown in the frame's settings. If none work (common
  with Docker setups or `https` vs `http` mix-ups), go to *Configure* on the integration in Home
  Assistant and set **"Home Assistant URL for the frame"**, e.g. `http://192.168.1.20:8123`.
- **No photos.** Use *Test connection* in the frame's settings. Check the API key's permissions,
  and that your chosen albums or people actually contain photos.
- **Wake word triggers too often, or not enough.** Change *Sensitivity* under *Voice* in the
  frame's settings.
- **Assist is slow or fails.** In Home Assistant, open *Settings → Voice assistants → your
  assistant → ⋮ → Debug* to see which step is slow.

## Build from source

You need JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleRelease   # APKs in app/build/outputs/apk/release/
./gradlew testReleaseUnitTest
```

Release builds are signed with the key from `release.keystore.properties` in `local.properties`
if you have one; otherwise with your debug key.

To test without real hardware or servers:

```bash
python3 tests/fake_immich.py              # fake Immich with generated photos on :2283
python3 tests/fake_ha.py                  # fake Home Assistant (weather, a light, a speaker) on :8123
python3 tests/fake_frame.py --code 123456 # fake frame, for working on the HA integration
pytest tests                              # HA integration tests (needs pytest-homeassistant-custom-component)
```

| Folder | What's in it |
|---|---|
| `app/` | Android app (Kotlin, Jetpack Compose) |
| `custom_components/photo_frame/` | Home Assistant integration |
| `docs/PROTOCOL.md` | How the frame and Home Assistant talk to each other |
| `tests/` | Fake servers and integration tests |

## Credits & licenses

- The code in this repository is released under the [MIT License](LICENSE).
- Wake word models in `app/src/main/assets/wakeword/` come from
  [openWakeWord](https://github.com/dscripka/openWakeWord) ("hey jarvis", "alexa", "hey mycroft"
  and the shared feature models). Their pre-trained models are licensed **CC BY-NC-SA 4.0**
  (non-commercial use only). "Ok nabu" comes from the
  [Home Assistant wake words collection](https://github.com/fwartner/home-assistant-wakewords-collection).
- Not affiliated with Immich or Home Assistant.
