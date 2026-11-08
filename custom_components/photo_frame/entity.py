"""Base entity for the Photo Frame integration."""

from __future__ import annotations

from typing import Any

from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.device_registry import DeviceInfo
from homeassistant.helpers.entity import EntityDescription
from homeassistant.helpers.update_coordinator import CoordinatorEntity

from .api import FrameCommandError, FrameError
from .const import DOMAIN
from .coordinator import PhotoFrameConfigEntry, PhotoFrameCoordinator


def get_path(state: dict[str, Any] | None, *path: str) -> Any:
    """Safely read a nested value from the frame state."""
    cur: Any = state
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


class PhotoFrameEntity(CoordinatorEntity[PhotoFrameCoordinator]):
    """Common base: device info, unique id, command helper."""

    _attr_has_entity_name = True

    def __init__(
        self,
        entry: PhotoFrameConfigEntry,
        description: EntityDescription,
    ) -> None:
        """Initialize the entity."""
        super().__init__(entry.runtime_data.coordinator)
        self.entity_description = description
        info = entry.runtime_data.info
        self._frame_id: str = info["id"]
        self._frame_client = entry.runtime_data.client
        self._attr_unique_id = f"{self._frame_id}_{description.key}"
        self._attr_device_info = DeviceInfo(
            identifiers={(DOMAIN, self._frame_id)},
            name=info.get("name") or entry.title,
            manufacturer=info.get("manufacturer"),
            model=info.get("model"),
            sw_version=info.get("app_version"),
        )

    @property
    def frame_state(self) -> dict[str, Any]:
        """Return the latest full state."""
        return self.coordinator.data or {}

    def frame_value(self, *path: str) -> Any:
        """Read a nested state value."""
        return get_path(self.coordinator.data, *path)

    async def send_command(
        self, command: str, params: dict[str, Any] | None = None
    ) -> Any:
        """Send a command, translating errors to HomeAssistantError."""
        try:
            return await self._frame_client.command(command, params)
        except FrameCommandError as err:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="command_failed",
                translation_placeholders={"command": command, "error": str(err)},
            ) from err
        except FrameError as err:
            raise HomeAssistantError(
                translation_domain=DOMAIN,
                translation_key="cannot_connect",
                translation_placeholders={"error": str(err)},
            ) from err
