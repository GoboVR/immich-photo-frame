"""Selects for the Photo Frame integration."""

from __future__ import annotations

from typing import Any

from homeassistant.components.select import SelectEntity, SelectEntityDescription
from homeassistant.const import EntityCategory
from homeassistant.core import HomeAssistant
from homeassistant.exceptions import ServiceValidationError
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .const import DOMAIN, SOURCES
from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity

PARALLEL_UPDATES = 0

SOURCE = SelectEntityDescription(
    key="source", translation_key="source", options=SOURCES
)
ALBUM = SelectEntityDescription(key="album", translation_key="album")
PERSON = SelectEntityDescription(key="person", translation_key="person")
WAKE_WORD = SelectEntityDescription(
    key="wake_word_model",
    translation_key="wake_word_model",
    entity_category=EntityCategory.CONFIG,
)


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up selects."""
    async_add_entities(
        [
            PhotoFrameSourceSelect(entry, SOURCE),
            PhotoFrameCollectionSelect(
                entry, ALBUM, collection="albums", source="album", ids_key="album_ids"
            ),
            PhotoFrameCollectionSelect(
                entry, PERSON, collection="people", source="people", ids_key="person_ids"
            ),
            PhotoFrameWakeWordSelect(entry, WAKE_WORD),
        ]
    )


class PhotoFrameSourceSelect(PhotoFrameEntity, SelectEntity):
    """Slideshow photo source."""

    @property
    def current_option(self) -> str | None:
        """Return the current source."""
        source = self.frame_value("slideshow", "source")
        return source if source in SOURCES else None

    async def async_select_option(self, option: str) -> None:
        """Change the source."""
        await self.send_command("set_source", {"source": option})


class PhotoFrameCollectionSelect(PhotoFrameEntity, SelectEntity):
    """Pick one album / person from `sources` and show only that."""

    def __init__(
        self,
        entry: PhotoFrameConfigEntry,
        description: SelectEntityDescription,
        *,
        collection: str,
        source: str,
        ids_key: str,
    ) -> None:
        """Initialize."""
        super().__init__(entry, description)
        self._collection = collection
        self._source = source
        self._ids_key = ids_key

    def _name_to_id(self) -> dict[str, str]:
        """Map display names (made unique) to ids, preserving frame order."""
        items = self.frame_value("sources", self._collection) or []
        mapping: dict[str, str] = {}
        for item in items:
            if not isinstance(item, dict) or not item.get("id"):
                continue
            base = str(item.get("name") or item["id"])
            name, n = base, 2
            while name in mapping:
                name = f"{base} ({n})"
                n += 1
            mapping[name] = str(item["id"])
        return mapping

    @property
    def options(self) -> list[str]:
        """Return available names."""
        return list(self._name_to_id())

    @property
    def current_option(self) -> str | None:
        """Return the selected name (first selected id that is known)."""
        if self.frame_value("slideshow", "source") != self._source:
            return None
        selected: list[Any] = self.frame_value("slideshow", self._ids_key) or []
        id_to_name = {v: k for k, v in self._name_to_id().items()}
        for sel in selected:
            if sel in id_to_name:
                return id_to_name[sel]
        return None

    async def async_select_option(self, option: str) -> None:
        """Show only the chosen album / person."""
        item_id = self._name_to_id().get(option)
        if item_id is None:
            raise ServiceValidationError(
                translation_domain=DOMAIN,
                translation_key="unknown_option",
                translation_placeholders={"option": option},
            )
        await self.send_command(
            "set_source", {"source": self._source, self._ids_key: [item_id]}
        )


class PhotoFrameWakeWordSelect(PhotoFrameEntity, SelectEntity):
    """Wake word model."""

    @property
    def options(self) -> list[str]:
        """Return the available wake words."""
        words = self.frame_value("assist", "available_wake_words") or []
        current = self.frame_value("assist", "wake_word")
        opts = [str(w) for w in words]
        if current and current not in opts:
            opts.append(str(current))
        return opts

    @property
    def current_option(self) -> str | None:
        """Return the current wake word."""
        return self.frame_value("assist", "wake_word")

    async def async_select_option(self, option: str) -> None:
        """Change the wake word, keeping the enabled flag as-is."""
        enabled = self.frame_value("assist", "wake_word_enabled")
        await self.send_command(
            "set_wake_word",
            {"enabled": True if enabled is None else bool(enabled), "wake_word": option},
        )
