"""The Photo Frame integration."""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.const import CONF_HOST, CONF_PORT, Platform
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import ConfigEntryAuthFailed, ConfigEntryNotReady
from homeassistant.helpers import config_validation as cv, device_registry as dr
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.dispatcher import async_dispatcher_send
from homeassistant.helpers.typing import ConfigType

from .api import FrameAuthError, FrameClient, FrameError
from .const import (
    CONF_HA_ACCESS,
    CONF_HA_REFRESH_TOKEN_ID,
    CONF_PROVISION_VERSION,
    PROVISION_VERSION,
    CONF_TOKEN,
    DOMAIN,
    EVENT_PHOTO_FRAME,
    SIGNAL_FRAME_EVENT,
)
from .coordinator import PhotoFrameConfigEntry, PhotoFrameCoordinator, PhotoFrameData
from .services import async_setup_services

_LOGGER = logging.getLogger(__name__)

PLATFORMS: list[Platform] = [
    Platform.BINARY_SENSOR,
    Platform.BUTTON,
    Platform.CAMERA,
    Platform.IMAGE,
    Platform.LIGHT,
    Platform.MEDIA_PLAYER,
    Platform.NUMBER,
    Platform.SELECT,
    Platform.SENSOR,
    Platform.SWITCH,
]

CONFIG_SCHEMA = cv.config_entry_only_config_schema(DOMAIN)


async def async_setup(hass: HomeAssistant, config: ConfigType) -> bool:
    """Set up the integration (register services)."""
    async_setup_services(hass)
    return True


async def async_setup_entry(hass: HomeAssistant, entry: PhotoFrameConfigEntry) -> bool:
    """Set up a frame from a config entry."""
    client = FrameClient(
        async_get_clientsession(hass),
        entry.data[CONF_HOST],
        entry.data[CONF_PORT],
        entry.data[CONF_TOKEN],
    )
    try:
        info = await client.get_info()
        await client.get_state()  # validates the token
    except FrameAuthError as err:
        raise ConfigEntryAuthFailed("Frame rejected the pairing token") from err
    except FrameError as err:
        raise ConfigEntryNotReady(f"Cannot reach frame: {err}") from err

    coordinator = PhotoFrameCoordinator(hass, entry, client)
    coordinator.async_set_updated_data(client.state or {})

    data = PhotoFrameData(client=client, coordinator=coordinator, info=info)
    entry.runtime_data = data

    # Make sure the device exists so events can reference its id immediately.
    device = dr.async_get(hass).async_get_or_create(
        config_entry_id=entry.entry_id,
        identifiers={(DOMAIN, info["id"])},
        name=info.get("name") or entry.title,
        manufacturer=info.get("manufacturer"),
        model=info.get("model"),
        sw_version=info.get("app_version"),
    )
    data.device_id = device.id

    @callback
    def _on_event(name: str, evdata: dict[str, Any]) -> None:
        hass.bus.async_fire(
            EVENT_PHOTO_FRAME,
            {"device_id": data.device_id, "event": name, "data": evdata},
        )
        async_dispatcher_send(
            hass, SIGNAL_FRAME_EVENT.format(entry.entry_id), name, evdata
        )

    @callback
    def _on_auth_failed() -> None:
        entry.async_start_reauth(hass)

    entry.async_on_unload(client.add_state_listener(coordinator.handle_state))
    entry.async_on_unload(client.add_connection_listener(coordinator.handle_connection))
    entry.async_on_unload(client.add_event_listener(_on_event))
    entry.async_on_unload(client.add_auth_failed_listener(_on_auth_failed))

    await hass.config_entries.async_forward_entry_setups(entry, PLATFORMS)

    client.start()

    # Older entries were provisioned with a single (often unreachable) HA URL.
    if (
        entry.data.get(CONF_HA_ACCESS)
        and entry.data.get(CONF_PROVISION_VERSION) != PROVISION_VERSION
    ):
        async def _reprovision() -> None:
            from .config_flow import async_reprovision  # avoid import cycle at load

            try:
                await async_reprovision(hass, entry)
            except Exception as err:  # noqa: BLE001 - best effort, logged
                _LOGGER.warning("Could not re-provision Home Assistant access: %s", err)

        entry.async_create_background_task(hass, _reprovision(), "photo_frame_reprovision")
    return True


async def async_unload_entry(hass: HomeAssistant, entry: PhotoFrameConfigEntry) -> bool:
    """Unload a config entry."""
    unload_ok = await hass.config_entries.async_unload_platforms(entry, PLATFORMS)
    if unload_ok:
        await entry.runtime_data.client.stop()
    return unload_ok


async def async_remove_entry(hass: HomeAssistant, entry: PhotoFrameConfigEntry) -> None:
    """Revoke the HA access token we created for this frame."""
    if (token_id := entry.data.get(CONF_HA_REFRESH_TOKEN_ID)) and (
        token := hass.auth.async_get_refresh_token(token_id)
    ):
        hass.auth.async_remove_refresh_token(token)
