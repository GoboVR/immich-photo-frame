"""Push-based coordinator for the Photo Frame integration."""

from __future__ import annotations

from dataclasses import dataclass
import logging
from typing import Any

from homeassistant.config_entries import ConfigEntry
from homeassistant.core import HomeAssistant, callback
from homeassistant.exceptions import ConfigEntryAuthFailed
from homeassistant.helpers.update_coordinator import DataUpdateCoordinator, UpdateFailed

from .api import FrameAuthError, FrameClient, FrameError
from .const import DOMAIN

_LOGGER = logging.getLogger(__name__)


@dataclass
class PhotoFrameData:
    """Runtime data stored on the config entry."""

    client: FrameClient
    coordinator: PhotoFrameCoordinator
    info: dict[str, Any]
    device_id: str | None = None


type PhotoFrameConfigEntry = ConfigEntry[PhotoFrameData]


class PhotoFrameCoordinator(DataUpdateCoordinator[dict[str, Any]]):
    """Holds the latest frame state; updated by WebSocket pushes.

    There is no polling interval: `_async_update_data` (REST /state) is only
    used for the first refresh. Availability follows the WebSocket connection.
    """

    config_entry: PhotoFrameConfigEntry

    def __init__(
        self, hass: HomeAssistant, entry: PhotoFrameConfigEntry, client: FrameClient
    ) -> None:
        """Initialize the coordinator."""
        super().__init__(
            hass,
            _LOGGER,
            config_entry=entry,
            name=f"{DOMAIN} {entry.title}",
            update_interval=None,
        )
        self.client = client

    async def _async_update_data(self) -> dict[str, Any]:
        try:
            return await self.client.get_state()
        except FrameAuthError as err:
            raise ConfigEntryAuthFailed(str(err)) from err
        except FrameError as err:
            raise UpdateFailed(str(err)) from err

    @callback
    def handle_state(self, state: dict[str, Any]) -> None:
        """Handle a full state push from the WebSocket."""
        self.async_set_updated_data(state)

    @callback
    def handle_connection(self, connected: bool) -> None:
        """Mark entities unavailable while the WebSocket is down."""
        if not connected:
            self.async_set_update_error(UpdateFailed("Disconnected from frame"))
        # On (re)connect the frame immediately sends a full state, which
        # restores availability via async_set_updated_data.
