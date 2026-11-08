"""Media player for the Photo Frame integration (speaker / TTS target)."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from homeassistant.components import media_source
from homeassistant.components.media_player import (
    ATTR_MEDIA_ANNOUNCE,
    ATTR_MEDIA_EXTRA,
    BrowseMedia,
    MediaPlayerDeviceClass,
    MediaPlayerEntity,
    MediaPlayerEntityDescription,
    MediaPlayerEntityFeature,
    MediaPlayerState,
    MediaType,
    async_process_play_media_url,
)
from homeassistant.core import HomeAssistant
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util import dt as dt_util

from .coordinator import PhotoFrameConfigEntry
from .entity import PhotoFrameEntity

PARALLEL_UPDATES = 0

MEDIA_PLAYER = MediaPlayerEntityDescription(
    key="media_player",
    name=None,
    device_class=MediaPlayerDeviceClass.SPEAKER,
)

STATE_MAP: dict[str, MediaPlayerState] = {
    "idle": MediaPlayerState.IDLE,
    "playing": MediaPlayerState.PLAYING,
    "paused": MediaPlayerState.PAUSED,
    "buffering": MediaPlayerState.BUFFERING,
}


async def async_setup_entry(
    hass: HomeAssistant,
    entry: PhotoFrameConfigEntry,
    async_add_entities: AddEntitiesCallback,
) -> None:
    """Set up the media player."""
    async_add_entities([PhotoFrameMediaPlayer(entry, MEDIA_PLAYER)])


class PhotoFrameMediaPlayer(PhotoFrameEntity, MediaPlayerEntity):
    """The frame's speaker. Independent of the screen power state."""

    _attr_media_image_remotely_accessible = False
    _attr_supported_features = (
        MediaPlayerEntityFeature.PLAY_MEDIA
        | MediaPlayerEntityFeature.BROWSE_MEDIA
        | MediaPlayerEntityFeature.VOLUME_SET
        | MediaPlayerEntityFeature.VOLUME_MUTE
        | MediaPlayerEntityFeature.PAUSE
        | MediaPlayerEntityFeature.PLAY
        | MediaPlayerEntityFeature.STOP
        | MediaPlayerEntityFeature.SEEK
        | MediaPlayerEntityFeature.MEDIA_ANNOUNCE
    )

    def _media(self, key: str) -> Any:
        return self.frame_value("media", key)

    @property
    def state(self) -> MediaPlayerState | None:
        """Return the playback state."""
        return STATE_MAP.get(self._media("state"), MediaPlayerState.IDLE)

    @property
    def volume_level(self) -> float | None:
        """Return volume 0..1."""
        vol = self._media("volume")
        return float(vol) if vol is not None else None

    @property
    def is_volume_muted(self) -> bool | None:
        """Return mute state."""
        return self._media("muted")

    @property
    def media_content_id(self) -> str | None:
        """Return the playing url."""
        return self._media("url")

    @property
    def media_content_type(self) -> MediaType | str | None:
        """Return the content type."""
        if not self._media("url"):
            return None
        return {"video": MediaType.VIDEO, "image": MediaType.IMAGE}.get(
            self._media("content") or "", MediaType.MUSIC
        )

    @property
    def media_title(self) -> str | None:
        """Return the title."""
        return self._media("title")

    @property
    def media_artist(self) -> str | None:
        """Return the artist."""
        return self._media("artist")

    @property
    def media_image_url(self) -> str | None:
        """Return album art url."""
        return self._media("album_art")

    @property
    def media_duration(self) -> int | None:
        """Return duration in seconds."""
        dur = self._media("duration")
        return int(dur) if dur is not None else None

    @property
    def media_position(self) -> int | None:
        """Return position in seconds."""
        pos = self._media("position")
        return int(pos) if pos is not None else None

    @property
    def media_position_updated_at(self) -> datetime | None:
        """Return when the position was last updated."""
        ts = self._media("position_updated_at")
        if not ts:
            return None
        return dt_util.parse_datetime(str(ts))

    async def async_media_play(self) -> None:
        """Resume playback."""
        await self.send_command("media_resume")

    async def async_media_pause(self) -> None:
        """Pause playback."""
        await self.send_command("media_pause")

    async def async_media_stop(self) -> None:
        """Stop playback."""
        await self.send_command("media_stop")

    async def async_media_seek(self, position: float) -> None:
        """Seek."""
        await self.send_command("media_seek", {"position": float(position)})

    async def async_set_volume_level(self, volume: float) -> None:
        """Set volume."""
        await self.send_command("set_volume", {"volume": max(0.0, min(1.0, volume))})

    async def async_mute_volume(self, mute: bool) -> None:
        """Mute/unmute."""
        await self.send_command("set_mute", {"muted": mute})

    async def async_play_media(
        self, media_type: MediaType | str, media_id: str, **kwargs: Any
    ) -> None:
        """Play a url or media source item (TTS, music, video, images, cameras, ...)."""
        params: dict[str, Any] = {"media_type": str(media_type)}
        if media_source.is_media_source_id(media_id):
            play_item = await media_source.async_resolve_media(
                self.hass, media_id, self.entity_id
            )
            media_id = play_item.url
            if play_item.mime_type:
                params["mime"] = play_item.mime_type
        if "/api/camera_proxy_stream/" in media_id:
            # Cameras without a video stream resolve to an MJPEG feed, which Android's
            # player can't decode. Send the snapshot URL and have the frame refresh it.
            media_id = media_id.replace("/api/camera_proxy_stream/", "/api/camera_proxy/")
            params["mime"] = "image/jpeg"
            params["refresh"] = 1
        # Sign HA-hosted urls but keep them relative: the frame prefixes whichever
        # HA address it can actually reach (HA's own idea of its URL often isn't).
        params["url"] = async_process_play_media_url(
            self.hass, media_id, allow_relative_url=True
        )

        if kwargs.get(ATTR_MEDIA_ANNOUNCE):
            params["announce"] = True

        extra = kwargs.get(ATTR_MEDIA_EXTRA) or {}
        if isinstance(extra, dict):
            # Metadata may be flat or nested under "metadata" (Music Assistant and
            # others); first non-empty key wins.
            sources = [extra]
            if isinstance(extra.get("metadata"), dict):
                sources.insert(0, extra["metadata"])

            def pick(*keys: str) -> Any:
                for src in sources:
                    for key in keys:
                        if src.get(key):
                            return src[key]
                return None

            if title := pick("title", "name"):
                params["title"] = title
            if artist := pick("artist", "artists", "albumartist"):
                params["artist"] = ", ".join(artist) if isinstance(artist, list) else artist
            thumb = pick("thumb", "thumbnail", "album_art", "image", "imageUrl", "image_url", "entity_picture")
            if not thumb:
                # Cast-style metadata (used by YouTube Music & others): images=[{"url": ...}]
                images = pick("images")
                if isinstance(images, list) and images:
                    first = images[0]
                    thumb = first.get("url") if isinstance(first, dict) else first
            if thumb:
                params["album_art"] = async_process_play_media_url(
                    self.hass, thumb, allow_relative_url=True
                ) if isinstance(thumb, str) and thumb.startswith("/") else thumb
            if "mime" not in params and (mime := extra.get("mime")):
                params["mime"] = mime
            # How long to show an image / video before going back to the photos.
            if isinstance(duration := extra.get("duration"), (int, float)) and duration > 0:
                params["duration"] = duration
            if isinstance(refresh := extra.get("refresh"), (int, float)) and refresh > 0:
                params["refresh"] = refresh

        await self.send_command("media_play", params)

    async def async_browse_media(
        self,
        media_content_type: MediaType | str | None = None,
        media_content_id: str | None = None,
    ) -> BrowseMedia:
        """Browse media sources: audio, video, images and cameras."""
        return await media_source.async_browse_media(
            self.hass,
            media_content_id,
            content_filter=lambda item: item.media_content_type.startswith(
                ("audio/", "video/", "image/", "application/vnd.apple.mpegurl", "application/x-mpegurl")
            ),
        )
