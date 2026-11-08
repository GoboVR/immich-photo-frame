"""Current photo image entity for the Photo Frame integration."""

from __future__ import annotations

import logging
from typing import Any

from homeassistant.components.image import ImageEntity, ImageEntityDescription
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers.dispatcher import async_dispatcher_connect
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .api import FrameError
from .const import EVENT_PHOTO_CHANGED, SIGNAL_FRAME_EVENT
from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity

_LOGGER = logging.getLogger(__name__)

PARALLEL_UPDATES = 0

CURRENT_PHOTO = ImageEntityDescription(
    key="current_photo", translation_key="current_photo"
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the image entity."""
    async_add_entities([PhotoFrameCurrentPhoto(hass, entry, CURRENT_PHOTO)])


class PhotoFrameCurrentPhoto(PhotoFrameEntity, ImageEntity):
    """The photo currently shown on the frame."""

    _attr_content_type = "image/jpeg"

    def __init__(
        self,
        hass: HomeAssistant,
        entry: PhotoFrameConfigEntry,
        description: ImageEntityDescription,
    ) -> None:
        """Initialize."""
        PhotoFrameEntity.__init__(self, entry, description)
        ImageEntity.__init__(self, hass)
        self._entry_id = entry.entry_id
        self._asset_id: str | None = self._current_asset_id()
        self._cached: bytes | None = None
        self._cached_for: str | None = None
        self._attr_image_last_updated = dt_util.utcnow()

    def _current_asset_id(self) -> str | None:
        return self.frame_value("slideshow", "current", "asset_id")

    async def async_added_to_hass(self) -> None:
        """Subscribe to frame events."""
        await super().async_added_to_hass()
        self.async_on_remove(
            async_dispatcher_connect(
                self.hass, SIGNAL_FRAME_EVENT.format(self._entry_id), self._on_event
            )
        )

    @callback
    def _bump(self, asset_id: str | None) -> None:
        self._asset_id = asset_id
        self._cached = None
        self._cached_for = None
        self._attr_image_last_updated = dt_util.utcnow()

    @callback
    def _on_event(self, name: str, data: dict[str, Any]) -> None:
        if name != EVENT_PHOTO_CHANGED:
            return
        self._bump(data.get("asset_id") or self._asset_id)
        self.async_write_ha_state()

    @callback
    def _handle_coordinator_update(self) -> None:
        asset_id = self._current_asset_id()
        if asset_id != self._asset_id:
            self._bump(asset_id)
        super()._handle_coordinator_update()

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """Return the asset id."""
        return {"asset_id": self._asset_id}

    async def async_image(self) -> bytes | None:
        """Fetch the current photo from the frame."""
        if self._cached is not None and self._cached_for == self._asset_id:
            return self._cached
        key = self._asset_id
        try:
            data = await self._frame_client.fetch_current_photo()
        except FrameError as err:
            _LOGGER.debug("Could not fetch current photo: %s", err)
            return self._cached
        self._cached = data
        self._cached_for = key
        return data
