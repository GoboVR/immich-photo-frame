"""End-to-end tests: real integration against the in-process fake frame."""

from __future__ import annotations

import asyncio
from collections.abc import Callable

from homeassistant.config_entries import SOURCE_USER, ConfigEntryState
from homeassistant.const import STATE_UNAVAILABLE
from homeassistant.core import HomeAssistant
from homeassistant.data_entry_flow import FlowResultType
from homeassistant.setup import async_setup_component

from custom_components.photo_frame.const import DOMAIN, EVENT_PHOTO_FRAME

from .conftest import PAIR_CODE


async def wait_for(hass: HomeAssistant, cond: Callable[[], bool], timeout: float = 5) -> None:
    """Poll until cond() is true."""
    loop = asyncio.get_running_loop()
    end = loop.time() + timeout
    while not cond():
        if loop.time() > end:
            raise AssertionError("condition not met in time")
        await asyncio.sleep(0.05)
        await hass.async_block_till_done()


async def _setup(hass: HomeAssistant, port: int, ha_access: bool = True):
    hass.config.internal_url = "http://10.0.0.2:8123"
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": SOURCE_USER}
    )
    assert result["type"] is FlowResultType.FORM
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {"host": "127.0.0.1", "port": port}
    )
    assert result["type"] is FlowResultType.FORM
    assert result["step_id"] == "pair"

    bad = await hass.config_entries.flow.async_configure(
        result["flow_id"], {"code": "000000", "ha_access": ha_access}
    )
    assert bad["errors"] == {"code": "invalid_code"}

    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {"code": PAIR_CODE, "ha_access": ha_access}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY, result
    await hass.async_block_till_done()
    return result["result"]


async def test_end_to_end(hass: HomeAssistant, fake_frame, hass_client) -> None:
    """Pair, read state, send commands, receive events, reconnect, reauth."""
    frame, port = fake_frame
    assert await async_setup_component(hass, "media_source", {})
    entry = await _setup(hass, port)
    assert entry.state is ConfigEntryState.LOADED
    assert entry.unique_id == frame.state["id"]

    # HA access provisioned with a dedicated non-admin user
    assert frame.state["ha"] == {"connected": True, "url": "http://10.0.0.2:8123"}
    prov = [c for c in frame.commands if c[0] == "provision_ha"][-1][1]
    assert prov["url"] == prov["urls"][0]
    assert "http://10.0.0.2:8123" in prov["urls"], prov["urls"]
    assert entry.data.get("provision_version") == 2
    users = [u for u in await hass.auth.async_get_users() if u.name == "Photo Frame"]
    assert len(users) == 1 and not users[0].is_admin and users[0].local_only

    await wait_for(hass, lambda: len(frame.clients) == 1)

    # --- states
    assert hass.states.get("media_player.fake_frame").state == "idle"
    light = hass.states.get("light.fake_frame_screen")
    assert light.state == "on" and light.attributes["brightness"] == 180
    assert hass.states.get("switch.fake_frame_slideshow").state == "on"
    assert hass.states.get("sensor.fake_frame_battery").state == "87"
    assert hass.states.get("sensor.fake_frame_photo_location").state == "Austin, United States"
    assert hass.states.get("sensor.fake_frame_photo_date").state == "2019-07-04"
    assert hass.states.get("sensor.fake_frame_assist_state").state == "idle"
    assert hass.states.get("binary_sensor.fake_frame_charging").state == "on"
    assert hass.states.get("number.fake_frame_slideshow_interval").state == "30"
    assert hass.states.get("select.fake_frame_photo_source").state == "mixed"
    album = hass.states.get("select.fake_frame_album")
    assert album.attributes["options"] == ["Family", "Vacation", "Family (2)"]
    assert hass.states.get("select.fake_frame_wake_word").state == "hey_jarvis"

    # --- commands (over WS)
    await hass.services.async_call(
        "light", "turn_on", {"entity_id": "light.fake_frame_screen", "brightness": 50}, blocking=True
    )
    await wait_for(hass, lambda: hass.states.get("light.fake_frame_screen").attributes.get("brightness") == 50)
    await hass.services.async_call(
        "light", "turn_off", {"entity_id": "light.fake_frame_screen"}, blocking=True
    )
    await wait_for(hass, lambda: hass.states.get("light.fake_frame_screen").state == "off")

    await hass.services.async_call(
        "select", "select_option",
        {"entity_id": "select.fake_frame_album", "option": "Vacation"}, blocking=True,
    )
    await wait_for(hass, lambda: hass.states.get("select.fake_frame_album").state == "Vacation")
    assert frame.state["slideshow"]["source"] == "album"
    assert frame.state["slideshow"]["album_ids"] == ["alb-2"]

    await hass.services.async_call(
        "number", "set_value",
        {"entity_id": "number.fake_frame_slideshow_interval", "value": 60}, blocking=True,
    )
    await hass.services.async_call(
        "switch", "turn_off", {"entity_id": "switch.fake_frame_wake_word_detection"}, blocking=True
    )
    await wait_for(hass, lambda: hass.states.get("switch.fake_frame_wake_word_detection").state == "off")
    await hass.services.async_call(
        "select", "select_option",
        {"entity_id": "select.fake_frame_wake_word", "option": "alexa"}, blocking=True,
    )
    assert frame.commands[-1] == ("set_wake_word", {"enabled": False, "wake_word": "alexa"})

    # --- media
    frame.media_length = 0.3
    await hass.services.async_call(
        "media_player", "play_media",
        {
            "entity_id": "media_player.fake_frame",
            "media_content_id": "http://example.com/a.mp3",
            "media_content_type": "music",
            "announce": True,
        },
        blocking=True,
    )
    cmd, params = frame.commands[-1]
    assert cmd == "media_play" and params["announce"] is True
    assert params["url"] == "http://example.com/a.mp3"
    await wait_for(hass, lambda: hass.states.get("media_player.fake_frame").state == "playing")
    await wait_for(hass, lambda: hass.states.get("media_player.fake_frame").state == "idle")

    # HA-hosted media stays relative (signed); the frame prefixes the HA url it can reach
    await hass.services.async_call(
        "media_player", "play_media",
        {
            "entity_id": "media_player.fake_frame",
            "media_content_id": "/api/tts_proxy/abc.mp3",
            "media_content_type": "music",
        },
        blocking=True,
    )
    url = frame.commands[-1][1]["url"]
    assert url.startswith("/api/tts_proxy/abc.mp3"), url
    await hass.services.async_call(
        "media_player", "media_stop", {"entity_id": "media_player.fake_frame"}, blocking=True
    )

    await hass.services.async_call(
        "media_player", "volume_set",
        {"entity_id": "media_player.fake_frame", "volume_level": 0.25}, blocking=True,
    )
    await wait_for(hass, lambda: hass.states.get("media_player.fake_frame").attributes.get("volume_level") == 0.25)

    # --- custom services
    await hass.services.async_call(
        DOMAIN, "show_message",
        {"message": "Hello", "title": "Hi", "duration": 5,
         "entity_id": "media_player.fake_frame"},
        blocking=True,
    )
    assert frame.commands[-1] == ("show_message", {"message": "Hello", "title": "Hi", "duration": 5})
    await hass.services.async_call(
        DOMAIN, "set_source",
        {"source": "people", "person_ids": ["p-2"], "config_entry_id": entry.entry_id},
        blocking=True,
    )
    await wait_for(hass, lambda: hass.states.get("select.fake_frame_person").state == "Ben")

    # --- events and image
    events = []
    hass.bus.async_listen(EVENT_PHOTO_FRAME, events.append)
    image_before = hass.states.get("image.fake_frame_current_photo").state
    await hass.services.async_call(
        "button", "press", {"entity_id": "button.fake_frame_next_photo"}, blocking=True
    )
    await frame.event("touch")
    await wait_for(hass, lambda: len(events) >= 2)
    assert {e.data["event"] for e in events} >= {"photo_changed", "touch"}
    assert events[0].data["device_id"] == entry.runtime_data.device_id
    await wait_for(hass, lambda: hass.states.get("image.fake_frame_current_photo").state != image_before)
    assert hass.states.get("image.fake_frame_current_photo").attributes["asset_id"] == "asset-2"

    client = await hass_client()
    resp = await client.get("/api/image_proxy/image.fake_frame_current_photo")
    assert resp.status == 200
    assert (await resp.read()).startswith(b"\x89PNG")

    # --- options flow -> set_widgets
    result = await hass.config_entries.options.async_init(entry.entry_id)
    assert result["type"] is FlowResultType.FORM
    result = await hass.config_entries.options.async_configure(
        result["flow_id"],
        {"weather_entity": "weather.home", "entities": ["light.kitchen"],
         "clock": True, "weather": False, "photo_info": True, "controls": True},
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    assert frame.state["widgets"]["weather"] is False
    assert frame.state["widgets"]["entities"] == ["light.kitchen"]

    # --- disconnect -> unavailable -> reconnect
    for ws in list(frame.clients):
        await ws.close()
    await wait_for(hass, lambda: hass.states.get("light.fake_frame_screen").state == STATE_UNAVAILABLE)
    await wait_for(hass, lambda: hass.states.get("light.fake_frame_screen").state == "off", timeout=10)

    # --- token revoked -> reauth flow
    frame.tokens.clear()
    for ws in list(frame.clients):
        await ws.close()
    await wait_for(
        hass,
        lambda: any(
            f["context"]["source"] == "reauth"
            for f in hass.config_entries.flow.async_progress_by_handler(DOMAIN)
        ),
        timeout=10,
    )
    flow = next(
        f for f in hass.config_entries.flow.async_progress_by_handler(DOMAIN)
        if f["context"]["source"] == "reauth"
    )
    result = await hass.config_entries.flow.async_configure(flow["flow_id"], {})
    assert result["step_id"] == "pair"
    result = await hass.config_entries.flow.async_configure(
        flow["flow_id"], {"code": PAIR_CODE, "ha_access": True}
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "reauth_successful"
    await hass.async_block_till_done()
    assert entry.state is ConfigEntryState.LOADED
    await wait_for(hass, lambda: len(frame.clients) == 1)
    # Only one LLAT for this frame remains
    user = users[0]
    assert sum(1 for t in user.refresh_tokens.values() if t.client_name == "Photo Frame Fake Frame") == 1

    assert await hass.config_entries.async_unload(entry.entry_id)
    await hass.async_block_till_done()


async def test_zeroconf_discovery(hass: HomeAssistant, fake_frame) -> None:
    """Zeroconf discovery leads to pairing; rediscovery updates host/port."""
    from ipaddress import ip_address

    try:
        from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
    except ImportError:
        from homeassistant.components.zeroconf import ZeroconfServiceInfo

    frame, port = fake_frame
    info = ZeroconfServiceInfo(
        ip_address=ip_address("127.0.0.1"),
        ip_addresses=[ip_address("127.0.0.1")],
        hostname="fake.local.",
        name="Fake Frame._photoframe._tcp.local.",
        port=port,
        type="_photoframe._tcp.local.",
        properties={"id": frame.state["id"], "name": "Fake Frame", "version": "1", "model": "x"},
    )
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": "zeroconf"}, data=info
    )
    assert result["step_id"] == "zeroconf_confirm"
    result = await hass.config_entries.flow.async_configure(result["flow_id"], {})
    assert result["step_id"] == "pair"
    result = await hass.config_entries.flow.async_configure(
        result["flow_id"], {"code": PAIR_CODE, "ha_access": False}
    )
    assert result["type"] is FlowResultType.CREATE_ENTRY
    entry = result["result"]
    assert entry.data["port"] == port
    await hass.async_block_till_done()

    # rediscovery -> already configured
    result = await hass.config_entries.flow.async_init(
        DOMAIN, context={"source": "zeroconf"}, data=info
    )
    assert result["type"] is FlowResultType.ABORT
    assert result["reason"] == "already_configured"

    assert await hass.config_entries.async_unload(entry.entry_id)
    await hass.async_block_till_done()


async def test_old_entry_reprovisions_with_url_list(hass: HomeAssistant, fake_frame) -> None:
    """Entries paired before URL candidates existed re-provision once on setup."""
    frame, port = fake_frame
    entry = await _setup(hass, port)
    # Simulate an entry created by the old version (single URL, no version marker).
    data = {k: v for k, v in entry.data.items() if k != "provision_version"}
    hass.config_entries.async_update_entry(entry, data=data)
    old_token_id = data["ha_refresh_token_id"]
    frame.commands.clear()

    assert await hass.config_entries.async_reload(entry.entry_id)
    await wait_for(hass, lambda: any(c[0] == "provision_ha" for c in frame.commands))
    await wait_for(hass, lambda: entry.data.get("provision_version") == 2)
    prov = [c for c in frame.commands if c[0] == "provision_ha"][-1][1]
    assert "http://10.0.0.2:8123" in prov["urls"]
    # old token revoked, new one stored
    assert entry.data["ha_refresh_token_id"] != old_token_id
    assert hass.auth.async_get_refresh_token(old_token_id) is None

    # A second reload must not re-provision again.
    frame.commands.clear()
    assert await hass.config_entries.async_reload(entry.entry_id)
    await asyncio.sleep(0.5)
    await hass.async_block_till_done()
    assert not any(c[0] == "provision_ha" for c in frame.commands)


async def test_cast_style_metadata(hass: HomeAssistant, fake_frame) -> None:
    """YouTube Music & co send Chromecast-style metadata; title/artist/art must reach the frame."""
    frame, port = fake_frame
    assert await async_setup_component(hass, "media_source", {})
    await _setup(hass, port)
    await hass.services.async_call(
        "media_player", "play_media",
        {
            "entity_id": "media_player.fake_frame",
            "media_content_id": "https://example.com/song.m4a",
            "media_content_type": "music",
            "extra": {"metadata": {
                "metadataType": 3, "title": "Steve's Lava Chicken", "artist": "Jack Black",
                "images": [{"url": "https://lh3.googleusercontent.com/art.jpg"}],
            }},
        },
        blocking=True,
    )
    cmd, params = frame.commands[-1]
    assert cmd == "media_play"
    assert params["title"] == "Steve's Lava Chicken"
    assert params["artist"] == "Jack Black"
    assert params["album_art"] == "https://lh3.googleusercontent.com/art.jpg"


async def test_video_image_and_camera(hass: HomeAssistant, fake_frame) -> None:
    """Video/image hints reach the frame; MJPEG cameras become refreshing snapshots."""
    frame, port = fake_frame
    assert await async_setup_component(hass, "media_source", {})
    await _setup(hass, port)

    async def play(content_id: str, content_type: str, extra: dict | None = None) -> dict:
        data = {"entity_id": "media_player.fake_frame", "media_content_id": content_id,
                "media_content_type": content_type}
        if extra:
            data["extra"] = extra
        await hass.services.async_call("media_player", "play_media", data, blocking=True)
        cmd, params = frame.commands[-1]
        assert cmd == "media_play"
        return params

    p = await play("https://example.com/clip.mp4", "video", {"duration": 30})
    assert p["media_type"] == "video" and p["duration"] == 30

    p = await play("/api/camera_proxy_stream/camera.front_door", "image")
    assert p["url"].startswith("/api/camera_proxy/camera.front_door"), p["url"]
    assert "authSig=" in p["url"]
    assert p["mime"] == "image/jpeg" and p["refresh"] == 1
