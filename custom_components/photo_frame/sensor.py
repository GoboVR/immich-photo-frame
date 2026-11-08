"""Sensors for the Photo Frame integration."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from homeassistant.components.sensor import (
    SensorDeviceClass,
    SensorEntity,
    SensorEntityDescription,
    SensorStateClass,
)
from homeassistant.const import (
    LIGHT_LUX,
    PERCENTAGE,
    SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
    EntityCategory,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.helpers.typing import StateType
from homeassistant.util import dt as dt_util

from .const import ASSIST_STATES
from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity, get_path

PARALLEL_UPDATES = 0


def _current(s: dict[str, Any]) -> dict[str, Any]:
    cur = get_path(s, "slideshow", "current")
    return cur if isinstance(cur, dict) else {}


def _location(s: dict[str, Any]) -> str | None:
    cur = _current(s)
    parts = [p for p in (cur.get("city"), cur.get("country")) if p]
    return ", ".join(parts) if parts else None


def _photo_date(s: dict[str, Any]) -> str | None:
    taken = _current(s).get("taken_at")
    if not taken:
        return None
    parsed = dt_util.parse_datetime(str(taken))
    if parsed is None:
        return str(taken)[:10]
    if parsed.tzinfo is None:
        return parsed.date().isoformat()
    return dt_util.as_local(parsed).date().isoformat()


def _assist_state(s: dict[str, Any]) -> str | None:
    value = get_path(s, "assist", "state")
    return value if value in ASSIST_STATES else None


def _photo_attrs(s: dict[str, Any]) -> dict[str, Any]:
    cur = _current(s)
    return {
        "asset_id": cur.get("asset_id"),
        "taken_at": cur.get("taken_at"),
        "city": cur.get("city"),
        "country": cur.get("country"),
        "description": cur.get("description"),
        "people": cur.get("people") or [],
    }


@dataclass(frozen=True, kw_only=True)
class PhotoFrameSensorDescription(SensorEntityDescription):
    """Describes a frame sensor."""

    value_fn: Callable[[dict[str, Any]], StateType | datetime]
    attrs_fn: Callable[[dict[str, Any]], dict[str, Any]] | None = None


SENSORS: tuple[PhotoFrameSensorDescription, ...] = (
    PhotoFrameSensorDescription(
        key="battery",
        device_class=SensorDeviceClass.BATTERY,
        native_unit_of_measurement=PERCENTAGE,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: get_path(s, "device", "battery"),
    ),
    PhotoFrameSensorDescription(
        key="illuminance",
        device_class=SensorDeviceClass.ILLUMINANCE,
        native_unit_of_measurement=LIGHT_LUX,
        state_class=SensorStateClass.MEASUREMENT,
        value_fn=lambda s: get_path(s, "device", "light_lux"),
    ),
    PhotoFrameSensorDescription(
        key="wifi_signal",
        translation_key="wifi_signal",
        device_class=SensorDeviceClass.SIGNAL_STRENGTH,
        native_unit_of_measurement=SIGNAL_STRENGTH_DECIBELS_MILLIWATT,
        state_class=SensorStateClass.MEASUREMENT,
        entity_category=EntityCategory.DIAGNOSTIC,
        value_fn=lambda s: get_path(s, "device", "wifi_rssi"),
    ),
    PhotoFrameSensorDescription(
        key="assist_state",
        translation_key="assist_state",
        device_class=SensorDeviceClass.ENUM,
        options=ASSIST_STATES,
        value_fn=_assist_state,
    ),
    PhotoFrameSensorDescription(
        key="photo_location",
        translation_key="photo_location",
        value_fn=_location,
        attrs_fn=_photo_attrs,
    ),
    PhotoFrameSensorDescription(
        key="photo_date",
        translation_key="photo_date",
        value_fn=_photo_date,
        attrs_fn=_photo_attrs,
    ),
)

UPTIME = SensorEntityDescription(
    key="uptime",
    translation_key="uptime",
    device_class=SensorDeviceClass.TIMESTAMP,
    entity_category=EntityCategory.DIAGNOSTIC,
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up sensors."""
    entities: list[SensorEntity] = [PhotoFrameSensor(entry, d) for d in SENSORS]
    entities.append(PhotoFrameUptimeSensor(entry, UPTIME))
    async_add_entities(entities)


class PhotoFrameSensor(PhotoFrameEntity, SensorEntity):
    """A frame sensor."""

    entity_description: PhotoFrameSensorDescription

    @property
    def native_value(self) -> StateType | datetime:
        """Return the state."""
        return self.entity_description.value_fn(self.frame_state)

    @property
    def extra_state_attributes(self) -> dict[str, Any] | None:
        """Return extra attributes."""
        if self.entity_description.attrs_fn is None:
            return None
        return self.entity_description.attrs_fn(self.frame_state)


class PhotoFrameUptimeSensor(PhotoFrameEntity, SensorEntity):
    """Boot time derived from `device.uptime` (seconds).

    Exposed as a timestamp (HA convention for uptime) and only moved when it
    drifts more than a minute, to avoid a state change on every push.
    """

    _boot: datetime | None = None

    @property
    def native_value(self) -> datetime | None:
        """Return the boot time."""
        uptime = self.frame_value("device", "uptime")
        if uptime is None:
            return self._boot
        try:
            boot = dt_util.utcnow() - timedelta(seconds=float(uptime))
        except (TypeError, ValueError):
            return self._boot
        if self._boot is None or abs((boot - self._boot).total_seconds()) > 60:
            self._boot = boot.replace(microsecond=0)
        return self._boot
