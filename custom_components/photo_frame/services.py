"""Services for the Photo Frame integration."""

from __future__ import annotations

from typing import Any

import voluptuous as vol

from homeassistant.config_entries import ConfigEntryState
from homeassistant.const import ATTR_DEVICE_ID, ATTR_ENTITY_ID
from homeassistant.core import HomeAssistant, ServiceCall, callback
from homeassistant.exceptions import HomeAssistantError, ServiceValidationError
from homeassistant.helpers import (
    config_validation as cv,
    device_registry as dr,
    entity_registry as er,
)

from .api import FrameCommandError, FrameError
from .const import (
    ATTR_ALBUM_IDS,
    ATTR_ASSET_ID,
    ATTR_CONFIG_ENTRY_ID,
    ATTR_DURATION,
    ATTR_ICON,
    ATTR_MESSAGE,
    ATTR_PERSON_IDS,
    ATTR_SOURCE,
    ATTR_TITLE,
    DOMAIN,
    SERVICE_SET_SOURCE,
    SERVICE_SHOW_ASSET,
    SERVICE_SHOW_MESSAGE,
    SOURCES,
)
from .coordinator import PhotoFrameConfigEntry

TARGET_FIELDS: dict[Any, Any] = {
    **cv.TARGET_SERVICE_FIELDS,
    vol.Optional(ATTR_CONFIG_ENTRY_ID): cv.string,
}

SHOW_MESSAGE_SCHEMA = vol.Schema(
    {
        **TARGET_FIELDS,
        vol.Required(ATTR_MESSAGE): cv.string,
        vol.Optional(ATTR_TITLE): cv.string,
        vol.Optional(ATTR_DURATION): vol.All(vol.Coerce(int), vol.Range(min=1, max=3600)),
        vol.Optional(ATTR_ICON): cv.string,
    }
)

SHOW_ASSET_SCHEMA = vol.Schema(
    {**TARGET_FIELDS, vol.Required(ATTR_ASSET_ID): cv.string}
)

SET_SOURCE_SCHEMA = vol.Schema(
    {
        **TARGET_FIELDS,
        vol.Required(ATTR_SOURCE): vol.In(SOURCES),
        vol.Optional(ATTR_ALBUM_IDS): vol.All(cv.ensure_list, [cv.string]),
        vol.Optional(ATTR_PERSON_IDS): vol.All(cv.ensure_list, [cv.string]),
    }
)

_TARGET_KEYS = {*cv.TARGET_SERVICE_FIELDS, ATTR_CONFIG_ENTRY_ID}


def _loaded_entries(hass: HomeAssistant) -> dict[str, PhotoFrameConfigEntry]:
    return {
        e.entry_id: e
        for e in hass.config_entries.async_entries(DOMAIN)
        if e.state is ConfigEntryState.LOADED
    }


def _resolve_entries(
    hass: HomeAssistant, call: ServiceCall
) -> list[PhotoFrameConfigEntry]:
    """Resolve targeted frames from config entry id, device and entity targets."""
    loaded = _loaded_entries(hass)
    wanted: set[str] = set()
    explicit = False

    if entry_id := call.data.get(ATTR_CONFIG_ENTRY_ID):
        explicit = True
        wanted.add(entry_id)

    dev_reg = dr.async_get(hass)
    for device_id in cv.ensure_list(call.data.get(ATTR_DEVICE_ID)):
        explicit = True
        if device := dev_reg.async_get(device_id):
            wanted.update(device.config_entries)

    ent_reg = er.async_get(hass)
    for entity_id in cv.ensure_list(call.data.get(ATTR_ENTITY_ID)):
        explicit = True
        if (ent := ent_reg.async_get(entity_id)) and ent.config_entry_id:
            wanted.add(ent.config_entry_id)

    if not explicit:
        if len(loaded) == 1:
            return list(loaded.values())
        raise ServiceValidationError(
            translation_domain=DOMAIN, translation_key="no_target"
        )

    entries = [loaded[eid] for eid in wanted if eid in loaded]
    if not entries:
        raise ServiceValidationError(
            translation_domain=DOMAIN, translation_key="target_not_found"
        )
    return entries


async def _send_all(
    hass: HomeAssistant, call: ServiceCall, command: str, params: dict[str, Any]
) -> None:
    for entry in _resolve_entries(hass, call):
        try:
            await entry.runtime_data.client.command(command, params)
        except FrameCommandError as err:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="command_failed",
                translation_placeholders={"command": command, "error": str(err)},
            ) from err
        except FrameError as err:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="cannot_connect",
                translation_placeholders={"error": str(err)},
            ) from err


def _params(call: ServiceCall) -> dict[str, Any]:
    return {k: v for k, v in call.data.items() if k not in _TARGET_KEYS}


@callback
def async_setup_services(hass: HomeAssistant) -> None:
    """Register integration services."""

    async def show_message(call: ServiceCall) -> None:
        await _send_all(hass, call, "show_message", _params(call))

    async def show_asset(call: ServiceCall) -> None:
        await _send_all(hass, call, "show_asset", _params(call))

    async def set_source(call: ServiceCall) -> None:
        await _send_all(hass, call, "set_source", _params(call))

    hass.services.async_register(
        DOMAIN, SERVICE_SHOW_MESSAGE, show_message, schema=SHOW_MESSAGE_SCHEMA
    )
    hass.services.async_register(
        DOMAIN, SERVICE_SHOW_ASSET, show_asset, schema=SHOW_ASSET_SCHEMA
    )
    hass.services.async_register(
        DOMAIN, SERVICE_SET_SOURCE, set_source, schema=SET_SOURCE_SCHEMA
    )
