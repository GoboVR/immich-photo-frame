"""Constants for the Photo Frame integration."""

from __future__ import annotations

from typing import Final

DOMAIN: Final = "photo_frame"

DEFAULT_PORT: Final = 8765
DEFAULT_NAME: Final = "Photo Frame"
PAIR_CLIENT_NAME: Final = "Home Assistant"

# Config entry data keys
CONF_FRAME_ID: Final = "frame_id"
CONF_TOKEN: Final = "token"
CONF_HA_ACCESS: Final = "ha_access"
CONF_CODE: Final = "code"
CONF_HA_USER_ID: Final = "ha_user_id"
CONF_HA_REFRESH_TOKEN_ID: Final = "ha_refresh_token_id"
CONF_HA_URL: Final = "ha_url"
CONF_PROVISION_VERSION: Final = "provision_version"
# Bump to make existing entries re-provision the frame on next setup.
PROVISION_VERSION: Final = 2

# Options keys (mirror the `widgets` object of the protocol)
CONF_WEATHER_ENTITY: Final = "weather_entity"
CONF_ENTITIES: Final = "entities"
CONF_WIDGET_CLOCK: Final = "clock"
CONF_WIDGET_WEATHER: Final = "weather"
CONF_WIDGET_PHOTO_INFO: Final = "photo_info"
CONF_WIDGET_CONTROLS: Final = "controls"
CONF_WIDGET_NOW_PLAYING: Final = "now_playing"
CONF_NOW_PLAYING_ENTITIES: Final = "now_playing_entities"
WIDGET_TOGGLES: Final = (
    CONF_WIDGET_CLOCK,
    CONF_WIDGET_WEATHER,
    CONF_WIDGET_PHOTO_INFO,
    CONF_WIDGET_CONTROLS,
    CONF_WIDGET_NOW_PLAYING,
)

QUICK_CONTROL_DOMAINS: Final = [
    "light",
    "switch",
    "fan",
    "cover",
    "scene",
    "script",
    "media_player",
    "climate",
    "lock",
]

# Dedicated HA user that the frame uses to talk back to Home Assistant
HA_USER_NAME: Final = "Photo Frame"

# Slideshow sources (protocol `slideshow.source`)
SOURCES: Final = ["album", "people", "memories", "random", "mixed"]

# Bus event fired for every WebSocket event from the frame
EVENT_PHOTO_FRAME: Final = "photo_frame_event"

# Dispatcher signal (format with entry_id) for frame events
SIGNAL_FRAME_EVENT: Final = "photo_frame_event_{}"

# Protocol event names
EVENT_PHOTO_CHANGED: Final = "photo_changed"

# Services
SERVICE_SHOW_MESSAGE: Final = "show_message"
SERVICE_SHOW_ASSET: Final = "show_asset"
SERVICE_SET_SOURCE: Final = "set_source"

ATTR_CONFIG_ENTRY_ID: Final = "config_entry_id"
ATTR_MESSAGE: Final = "message"
ATTR_TITLE: Final = "title"
ATTR_DURATION: Final = "duration"
ATTR_ICON: Final = "icon"
ATTR_ASSET_ID: Final = "asset_id"
ATTR_SOURCE: Final = "source"
ATTR_ALBUM_IDS: Final = "album_ids"
ATTR_PERSON_IDS: Final = "person_ids"

# Assist states. The protocol only documents "idle"; the rest are the states
# an Assist pipeline client typically goes through. Unknown values map to None.
ASSIST_STATES: Final = [
    "idle",
    "wake_word",
    "listening",
    "processing",
    "responding",
    "speaking",
    "error",
]
