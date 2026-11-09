"""Live camera (the frame's front camera) for the Photo Frame integration."""

from __future__ import annotations

import logging

import aiohttp
from aiohttp import web

from homeassistant.components.camera import Camera, CameraEntityDescription, CameraEntityFeature
from homeassistant.core import HomeAssistant
from homeassistant.helpers.aiohttp_client import (
    async_aiohttp_proxy_web,
    async_get_clientsession,
)
from homeassistant.helpers.entity_platform import AddEntitiesCallback

from .api import FrameError
from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity, get_path

_LOGGER = logging.getLogger(__name__)

PARALLEL_UPDATES = 0

CAMERA = CameraEntityDescription(key="camera", translation_key="camera")


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the camera (only for frame versions that have one)."""
    if get_path(entry.runtime_data.coordinator.data, "camera") is None:
        return
    async_add_entities([PhotoFrameCamera(entry, CAMERA)])


class PhotoFrameCamera(PhotoFrameEntity, Camera):
    """The frame's camera as MJPEG. The frame only runs it while someone watches."""

    _attr_supported_features = CameraEntityFeature.ON_OFF

    def __init__(self, entry: PhotoFrameConfigEntry, description: CameraEntityDescription) -> None:
        """Initialize."""
        PhotoFrameEntity.__init__(self, entry, description)
        Camera.__init__(self)

    @property
    def available(self) -> bool:
        """Available when the frame is online and has a usable camera."""
        return super().available and bool(self.frame_value("camera", "available"))

    @property
    def is_on(self) -> bool:
        """Camera turned on in the frame (it may still be idle)."""
        return bool(self.frame_value("camera", "enabled"))

    @property
    def is_streaming(self) -> bool:
        """Someone is watching right now."""
        return bool(self.frame_value("camera", "streaming"))

    async def async_camera_image(
        self, width: int | None = None, height: int | None = None
    ) -> bytes | None:
        """Return a fresh snapshot."""
        if not self.is_on:
            return None
        try:
            return await self._frame_client.fetch_camera_snapshot()
        except FrameError as err:
            _LOGGER.debug("Camera snapshot failed: %s", err)
            return None

    async def handle_async_mjpeg_stream(self, request: web.Request) -> web.StreamResponse | None:
        """Proxy the frame's MJPEG stream to the HA frontend."""
        if not self.is_on:
            return None
        session = async_get_clientsession(self.hass)
        stream_coro = session.get(
            self._frame_client.camera_mjpeg_url,
            headers=self._frame_client.auth_headers(),
            timeout=aiohttp.ClientTimeout(total=None, sock_connect=10),
        )
        return await async_aiohttp_proxy_web(self.hass, request, stream_coro)

    async def async_turn_on(self) -> None:
        """Turn the frame's camera on."""
        await self.send_command("set_camera", {"enabled": True})

    async def async_turn_off(self) -> None:
        """Turn the frame's camera off."""
        await self.send_command("set_camera", {"enabled": False})
