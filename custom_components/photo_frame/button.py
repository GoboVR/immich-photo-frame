"""Buttons for the Photo Frame integration."""

from __future__ import annotations

from dataclasses import dataclass

from homeassistant.components.button import ButtonEntity, ButtonEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity

PARALLEL_UPDATES = 0


@dataclass(frozen=True, kw_only=True)
class PhotoFrameButtonDescription(ButtonEntityDescription):
    """Describes a frame button."""

    command: str


BUTTONS: tuple[PhotoFrameButtonDescription, ...] = (
    PhotoFrameButtonDescription(
        key="next_photo", translation_key="next_photo", command="slideshow_next"
    ),
    PhotoFrameButtonDescription(
        key="previous_photo",
        translation_key="previous_photo",
        command="slideshow_previous",
    ),
    PhotoFrameButtonDescription(
        key="assist_start", translation_key="assist_start", command="assist_start"
    ),
    PhotoFrameButtonDescription(
        key="reload_sources",
        translation_key="reload_sources",
        command="reload_sources",
        entity_category=EntityCategory.CONFIG,
    ),
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up buttons."""
    async_add_entities(PhotoFrameButton(entry, desc) for desc in BUTTONS)


class PhotoFrameButton(PhotoFrameEntity, ButtonEntity):
    """A frame button."""

    entity_description: PhotoFrameButtonDescription

    async def async_press(self) -> None:
        """Press the button."""
        await self.send_command(self.entity_description.command)
