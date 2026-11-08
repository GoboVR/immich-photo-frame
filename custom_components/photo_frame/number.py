"""Numbers for the Photo Frame integration."""

from __future__ import annotations

from homeassistant.components.number import (
    NumberDeviceClass,
    NumberEntity,
    NumberEntityDescription,
    NumberMode,
)
from homeassistant.const import EntityCategory, UnitOfTime
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity

PARALLEL_UPDATES = 0

INTERVAL = NumberEntityDescription(
    key="slideshow_interval",
    translation_key="slideshow_interval",
    device_class=NumberDeviceClass.DURATION,
    native_unit_of_measurement=UnitOfTime.SECONDS,
    native_min_value=5,
    native_max_value=3600,
    native_step=1,
    mode=NumberMode.BOX,
    entity_category=EntityCategory.CONFIG,
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up numbers."""
    async_add_entities([PhotoFrameIntervalNumber(entry, INTERVAL)])


class PhotoFrameIntervalNumber(PhotoFrameEntity, NumberEntity):
    """Slideshow interval in seconds."""

    @property
    def native_value(self) -> float | None:
        """Return the interval."""
        return self.frame_value("slideshow", "interval")

    async def async_set_native_value(self, value: float) -> None:
        """Set the interval."""
        await self.send_command("set_interval", {"seconds": int(round(value))})
