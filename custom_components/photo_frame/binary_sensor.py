"""Binary sensors for the Photo Frame integration."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.components.binary_sensor import (
    BinarySensorDeviceClass,
    BinarySensorEntity,
    BinarySensorEntityDescription,
)
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity, get_path

PARALLEL_UPDATES = 0


@dataclass(frozen=True, kw_only=True)
class PhotoFrameBinarySensorDescription(BinarySensorEntityDescription):
    """Describes a frame binary sensor."""

    value_fn: Callable[[dict[str, Any]], bool | None]


BINARY_SENSORS: tuple[PhotoFrameBinarySensorDescription, ...] = (
    PhotoFrameBinarySensorDescription(
        key="charging",
        device_class=BinarySensorDeviceClass.BATTERY_CHARGING,
        value_fn=lambda s: get_path(s, "device", "charging"),
    ),
    PhotoFrameBinarySensorDescription(
        key="ha_connected",
        translation_key="ha_connected",
        device_class=BinarySensorDeviceClass.CONNECTIVITY,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: get_path(s, "ha", "connected"),
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up binary sensors."""
    async_add_entities(
        PhotoFrameBinarySensor(entry, desc) for desc in BINARY_SENSORS
    )


class PhotoFrameBinarySensor(PhotoFrameEntity, BinarySensorEntity):
    """A frame binary sensor."""

    entity_description: PhotoFrameBinarySensorDescription

    @property
    def is_on(self) -> bool | None:
        """Return the state."""
        return self.entity_description.value_fn(self.frame_state)

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        """Expose the HA url the frame uses."""
        if self.entity_description.key == "ha_connected":
            return {"url": get_path(self.frame_state, "ha", "url")}
        return None
