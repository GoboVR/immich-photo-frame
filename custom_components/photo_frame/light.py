"""Screen light for the Photo Frame integration."""

from __future__ import annotations

from typing import Any

from homeassistant.components.light import (
    ATTR_BRIGHTNESS,
    ColorMode,
    LightEntity,
    LightEntityDescription,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity

PARALLEL_UPDATES = 0

SCREEN = LightEntityDescription(key="screen", translation_key="screen")


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the screen light."""
    async_add_entities([PhotoFrameScreen(entry, SCREEN)])


class PhotoFrameScreen(PhotoFrameEntity, LightEntity):
    """The frame's display as a dimmable light."""

    _attr_color_mode = ColorMode.BRIGHTNESS
    _attr_supported_color_modes = {ColorMode.BRIGHTNESS}

    @property
    def is_on(self) -> bool | None:
        """Return True if the screen is on."""
        return self.frame_value("screen", "on")

    @property
    def brightness(self) -> int | None:
        """Return the screen brightness (0-255)."""
        value = self.frame_value("screen", "brightness")
        return int(value) if value is not None else None

    @property
    def extra_state_attributes(self) -> dict[str, Any]:
        """Return extra attributes."""
        return {
            "auto_brightness": self.frame_value("screen", "auto_brightness"),
            "night_mode": self.frame_value("screen", "night_mode"),
        }

    async def async_turn_on(self, **kwargs: Any) -> None:
        """Turn the screen on and/or set brightness."""
        if not self.is_on:
            await self.send_command("screen_on")
        if (brightness := kwargs.get(ATTR_BRIGHTNESS)) is not None:
            await self.send_command(
                "set_brightness", {"brightness": max(0, min(255, int(brightness)))}
            )

    async def async_turn_off(self, **kwargs: Any) -> None:
        """Turn the screen off."""
        await self.send_command("screen_off")
