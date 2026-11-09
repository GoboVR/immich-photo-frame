"""Switches for the Photo Frame integration."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from homeassistant.components.switch import SwitchEntity, SwitchEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity, get_path

PARALLEL_UPDATES = 0


@dataclass(frozen=True, kw_only=True)
class PhotoFrameSwitchDescription(SwitchEntityDescription):
    """Describes a frame switch."""

    value_fn: Callable[[dict[str, Any]], bool | None]
    # Returns (command, params) for the desired on/off state.
    command_fn: Callable[[bool, dict[str, Any]], tuple[str, dict[str, Any]]]


SWITCHES: tuple[PhotoFrameSwitchDescription, ...] = (
    PhotoFrameSwitchDescription(
        key="slideshow",
        translation_key="slideshow",
        value_fn=lambda s: get_path(s, "slideshow", "playing"),
        command_fn=lambda on, s: ("slideshow_play" if on else "slideshow_pause", {}),
    ),
    PhotoFrameSwitchDescription(
        key="night_mode",
        translation_key="night_mode",
        value_fn=lambda s: get_path(s, "screen", "night_mode"),
        command_fn=lambda on, s: ("set_night_mode", {"enabled": on}),
    ),
    PhotoFrameSwitchDescription(
        key="auto_brightness",
        translation_key="auto_brightness",
        entity_category=EntityCategory.CONFIG,
        value_fn=lambda s: get_path(s, "screen", "auto_brightness"),
        command_fn=lambda on, s: ("set_auto_brightness", {"enabled": on}),
    ),
    PhotoFrameSwitchDescription(
        key="camera",
        translation_key="camera",
        value_fn=lambda s: get_path(s, "camera", "enabled"),
        command_fn=lambda on, s: ("set_camera", {"enabled": on}),
    ),
    PhotoFrameSwitchDescription(
        key="wake_word",
        translation_key="wake_word",
        entity_category=EntityCategory.CONFIG,
        value_fn=lambda s: get_path(s, "assist", "wake_word_enabled"),
        command_fn=lambda on, s: ("set_wake_word", {"enabled": on}),
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up switches."""
    has_camera = get_path(entry.runtime_data.coordinator.data, "camera") is not None
    async_add_entities(
        PhotoFrameSwitch(entry, desc)
        for desc in SWITCHES
        # Older app versions don't have a camera: don't add a switch that can't work.
        if desc.key != "camera" or has_camera
    )


class PhotoFrameSwitch(PhotoFrameEntity, SwitchEntity):
    """A frame switch."""

    entity_description: PhotoFrameSwitchDescription

    @property
    def is_on(self) -> bool | None:
        """Return the switch state."""
        return self.entity_description.value_fn(self.frame_state)

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Turn on."""
        await self.send_command(*self.entity_description.command_fn(True, self.frame_state))

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Turn off."""
        await self.send_command(*self.entity_description.command_fn(False, self.frame_state))
