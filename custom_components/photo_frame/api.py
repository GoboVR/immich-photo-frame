"""Client for the Photo Frame HTTP + WebSocket protocol (v1).

This module has no Home Assistant dependencies so it can be reused/tested on
its own. See docs/PROTOCOL.md for the wire protocol.
"""

from __future__ import annotations

import asyncio
from collections.abc import Callable
import contextlib
import itertools
import logging
from typing import Any

import aiohttp

_LOGGER = logging.getLogger(__name__)

API_PREFIX = "/api/v1"
REQUEST_TIMEOUT = aiohttp.ClientTimeout(total=15)
COMMAND_TIMEOUT = 15.0
PING_INTERVAL = 30.0
# If nothing (not even a pong) arrives for this long, the socket is considered dead.
RECEIVE_TIMEOUT = PING_INTERVAL * 2 + 10
BACKOFF_INITIAL = 1.0
BACKOFF_MAX = 60.0

StateCallback = Callable[[dict[str, Any]], None]
EventCallback = Callable[[str, dict[str, Any]], None]
ConnectionCallback = Callable[[bool], None]
AuthFailedCallback = Callable[[], None]


class FrameError(Exception):
    """Base error for the frame client."""


class FrameConnectionError(FrameError):
    """The frame could not be reached."""


class FrameAuthError(FrameError):
    """The token was rejected (401)."""


class FrameInvalidCodeError(FrameError):
    """The pairing code was rejected."""


class FrameCommandError(FrameError):
    """The frame reported a command failure (`success: false`)."""


class FrameClient:
    """Talks to one frame over REST and a persistent WebSocket."""

    def __init__(
        self,
        session: aiohttp.ClientSession,
        host: str,
        port: int,
        token: str | None = None,
    ) -> None:
        """Initialize the client."""
        self._session = session
        self.host = host
        self.port = port
        self.token = token

        self._ws: aiohttp.ClientWebSocketResponse | None = None
        self._ws_task: asyncio.Task[None] | None = None
        self._ping_task: asyncio.Task[None] | None = None
        self._stopping = False
        self._auth_failed = False
        self._connected = False
        self._msg_id = itertools.count(1)
        self._pending: dict[int, asyncio.Future[dict[str, Any]]] = {}

        self._state_callbacks: list[StateCallback] = []
        self._event_callbacks: list[EventCallback] = []
        self._connection_callbacks: list[ConnectionCallback] = []
        self._auth_failed_callbacks: list[AuthFailedCallback] = []

        self.state: dict[str, Any] | None = None

    # ------------------------------------------------------------------ utils

    @property
    def base_url(self) -> str:
        """Return the base HTTP url of the frame."""
        host = self.host
        if ":" in host and not host.startswith("["):
            host = f"[{host}]"  # IPv6 literal
        return f"http://{host}:{self.port}"

    @property
    def connected(self) -> bool:
        """Return True while the WebSocket is connected."""
        return self._connected

    def _headers(self) -> dict[str, str]:
        if self.token:
            return {"Authorization": f"Bearer {self.token}"}
        return {}

    # -------------------------------------------------------------- callbacks

    @staticmethod
    def _add(lst: list[Any], cb: Any) -> Callable[[], None]:
        lst.append(cb)

        def _remove() -> None:
            with contextlib.suppress(ValueError):
                lst.remove(cb)

        return _remove

    def add_state_listener(self, cb: StateCallback) -> Callable[[], None]:
        """Register a callback for full state pushes. Returns an unsubscribe fn."""
        return self._add(self._state_callbacks, cb)

    def add_event_listener(self, cb: EventCallback) -> Callable[[], None]:
        """Register a callback for frame events (name, data)."""
        return self._add(self._event_callbacks, cb)

    def add_connection_listener(self, cb: ConnectionCallback) -> Callable[[], None]:
        """Register a callback for WebSocket connect/disconnect."""
        return self._add(self._connection_callbacks, cb)

    def add_auth_failed_listener(self, cb: AuthFailedCallback) -> Callable[[], None]:
        """Register a callback invoked when the token is rejected."""
        return self._add(self._auth_failed_callbacks, cb)

    def _set_connected(self, connected: bool) -> None:
        if connected == self._connected:
            return
        self._connected = connected
        if self._stopping and not self._auth_failed:
            # Intentional shutdown: don't report a disconnect.
            return
        for cb in list(self._connection_callbacks):
            try:
                cb(connected)
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Error in connection callback")

    def _notify_auth_failed(self) -> None:
        for cb in list(self._auth_failed_callbacks):
            try:
                cb()
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Error in auth-failed callback")

    # ------------------------------------------------------------------- REST

    async def _request(
        self,
        method: str,
        path: str,
        *,
        json: Any = None,
        auth: bool = True,
    ) -> Any:
        url = f"{self.base_url}{API_PREFIX}{path}"
        headers = self._headers() if auth else {}
        try:
            async with self._session.request(
                method, url, json=json, headers=headers, timeout=REQUEST_TIMEOUT
            ) as resp:
                if resp.status == 401:
                    body = await _safe_json(resp)
                    if isinstance(body, dict) and body.get("error") == "invalid_code":
                        raise FrameInvalidCodeError("invalid_code")
                    raise FrameAuthError(f"Unauthorized: {path}")
                if resp.status >= 400:
                    body = await _safe_json(resp)
                    raise FrameError(f"HTTP {resp.status} for {path}: {body}")
                return await _safe_json(resp)
        except (aiohttp.ClientError, TimeoutError, OSError) as err:
            raise FrameConnectionError(f"Error talking to {url}: {err}") from err

    async def get_info(self) -> dict[str, Any]:
        """GET /info (no auth)."""
        data = await self._request("GET", "/info", auth=False)
        if not isinstance(data, dict) or "id" not in data:
            raise FrameError(f"Unexpected /info response: {data}")
        return data

    async def pair_start(self) -> None:
        """POST /pair/start — the frame shows a 6-digit code."""
        await self._request("POST", "/pair/start", json={}, auth=False)

    async def pair_finish(self, code: str, client_name: str) -> dict[str, Any]:
        """POST /pair/finish. Returns {"token", "id"}; stores the token."""
        data = await self._request(
            "POST",
            "/pair/finish",
            json={"code": code, "client_name": client_name},
            auth=False,
        )
        if not isinstance(data, dict) or not data.get("token"):
            raise FrameError(f"Unexpected /pair/finish response: {data}")
        self.token = data["token"]
        return data

    async def get_state(self) -> dict[str, Any]:
        """GET /state."""
        data = await self._request("GET", "/state")
        if not isinstance(data, dict):
            raise FrameError(f"Unexpected /state response: {data}")
        self.state = data
        return data

    async def _rest_command(self, command: str, params: dict[str, Any]) -> Any:
        data = await self._request(
            "POST", "/command", json={"command": command, "params": params}
        )
        if not isinstance(data, dict):
            raise FrameError(f"Unexpected /command response: {data}")
        if not data.get("success"):
            raise FrameCommandError(data.get("error") or f"{command} failed")
        return data.get("result")

    async def fetch_current_photo(self) -> bytes | None:
        """GET /current_photo.jpg. Returns None on 404."""
        url = f"{self.base_url}{API_PREFIX}/current_photo.jpg"
        try:
            async with self._session.get(
                url, headers=self._headers(), timeout=REQUEST_TIMEOUT
            ) as resp:
                if resp.status == 404:
                    return None
                if resp.status == 401:
                    raise FrameAuthError("Unauthorized: current_photo.jpg")
                resp.raise_for_status()
                return await resp.read()
        except (aiohttp.ClientError, TimeoutError, OSError) as err:
            raise FrameConnectionError(f"Error fetching {url}: {err}") from err

    @property
    def camera_mjpeg_url(self) -> str:
        """Live MJPEG stream of the frame's camera (needs auth_headers())."""
        return f"{self.base_url}{API_PREFIX}/camera.mjpeg"

    def auth_headers(self) -> dict[str, str]:
        """Headers that authenticate us to the frame."""
        return self._headers()

    async def fetch_camera_snapshot(self) -> bytes | None:
        """GET /camera.jpg. Returns None if the camera is off or has no frame."""
        url = f"{self.base_url}{API_PREFIX}/camera.jpg"
        try:
            async with self._session.get(
                url, headers=self._headers(), timeout=aiohttp.ClientTimeout(total=12)
            ) as resp:
                if resp.status in (404, 503):
                    return None
                if resp.status == 401:
                    raise FrameAuthError("Unauthorized: camera.jpg")
                resp.raise_for_status()
                return await resp.read()
        except (aiohttp.ClientError, TimeoutError, OSError) as err:
            raise FrameConnectionError(f"Error fetching {url}: {err}") from err

    # ---------------------------------------------------------------- command

    async def command(self, command: str, params: dict[str, Any] | None = None) -> Any:
        """Send a command; over WS when connected, otherwise via REST."""
        params = params or {}
        ws = self._ws
        if self._connected and ws is not None and not ws.closed:
            msg_id = next(self._msg_id)
            fut: asyncio.Future[dict[str, Any]] = (
                asyncio.get_running_loop().create_future()
            )
            self._pending[msg_id] = fut
            try:
                try:
                    await ws.send_json(
                        {
                            "type": "command",
                            "id": msg_id,
                            "command": command,
                            "params": params,
                        }
                    )
                except (aiohttp.ClientError, ConnectionError, RuntimeError) as err:
                    _LOGGER.debug("WS send failed (%s), falling back to REST", err)
                    return await self._rest_command(command, params)
                try:
                    result = await asyncio.wait_for(fut, COMMAND_TIMEOUT)
                except _WsLost as err:
                    # Not retried via REST: the frame may already have executed
                    # it and most commands (e.g. slideshow_next) aren't idempotent.
                    raise FrameConnectionError(
                        f"Connection lost while waiting for result of {command}"
                    ) from err
                except TimeoutError as err:
                    raise FrameConnectionError(
                        f"Timeout waiting for result of {command}"
                    ) from err
            finally:
                self._pending.pop(msg_id, None)
            if not result.get("success"):
                raise FrameCommandError(result.get("error") or f"{command} failed")
            return result.get("result")
        return await self._rest_command(command, params)

    # -------------------------------------------------------------- websocket

    def start(self) -> None:
        """Start the persistent WebSocket connection (with auto-reconnect)."""
        self._stopping = False
        self._auth_failed = False
        if self._ws_task is None or self._ws_task.done():
            self._ws_task = asyncio.create_task(self._ws_loop(), name="photo_frame_ws")

    async def stop(self) -> None:
        """Stop the WebSocket and cancel pending work."""
        self._stopping = True
        if self._ws is not None and not self._ws.closed:
            with contextlib.suppress(Exception):
                await self._ws.close()
        if self._ws_task is not None:
            self._ws_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await self._ws_task
            self._ws_task = None
        self._fail_pending()
        self._set_connected(False)

    def _fail_pending(self) -> None:
        for fut in self._pending.values():
            if not fut.done():
                fut.set_exception(_WsLost())
        self._pending.clear()

    async def _ws_loop(self) -> None:
        backoff = BACKOFF_INITIAL
        url = f"{self.base_url}{API_PREFIX}/ws"
        while not self._stopping:
            try:
                async with self._session.ws_connect(
                    url, headers=self._headers(), heartbeat=None, autoping=True
                ) as ws:
                    self._ws = ws
                    _LOGGER.debug("Connected to %s", url)
                    self._set_connected(True)
                    backoff = BACKOFF_INITIAL
                    self._ping_task = asyncio.create_task(self._ping_loop(ws))
                    await self._read_loop(ws)
            except aiohttp.WSServerHandshakeError as err:
                if err.status == 401:
                    _LOGGER.warning("Frame at %s rejected our token", self.base_url)
                    self._stopping = True
                    self._auth_failed = True
                    self._cleanup_ws()
                    self._notify_auth_failed()
                    return
                _LOGGER.debug("WS handshake failed: %s", err)
            except asyncio.CancelledError:
                self._cleanup_ws()
                raise
            except (aiohttp.ClientError, TimeoutError, OSError) as err:
                _LOGGER.debug("WS connection error: %s", err)
            except Exception:  # noqa: BLE001
                _LOGGER.exception("Unexpected error in frame WebSocket loop")
            self._cleanup_ws()
            if self._stopping:
                return
            _LOGGER.debug("Reconnecting to %s in %.0fs", url, backoff)
            await asyncio.sleep(backoff)
            backoff = min(backoff * 2, BACKOFF_MAX)

    def _cleanup_ws(self) -> None:
        if self._ping_task is not None:
            self._ping_task.cancel()
            self._ping_task = None
        self._ws = None
        self._fail_pending()
        self._set_connected(False)

    async def _ping_loop(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        try:
            while not ws.closed:
                await asyncio.sleep(PING_INTERVAL)
                await ws.send_json({"type": "ping"})
        except (aiohttp.ClientError, ConnectionError, RuntimeError):
            pass

    async def _read_loop(self, ws: aiohttp.ClientWebSocketResponse) -> None:
        while True:
            try:
                msg = await ws.receive(timeout=RECEIVE_TIMEOUT)
            except TimeoutError:
                _LOGGER.debug("No message from frame in %ss, reconnecting", RECEIVE_TIMEOUT)
                await ws.close()
                return
            if msg.type == aiohttp.WSMsgType.TEXT:
                try:
                    data = msg.json()
                except ValueError:
                    _LOGGER.debug("Ignoring non-JSON message: %s", msg.data)
                    continue
                if isinstance(data, dict):
                    self._handle_message(data)
            elif msg.type in (
                aiohttp.WSMsgType.CLOSE,
                aiohttp.WSMsgType.CLOSING,
                aiohttp.WSMsgType.CLOSED,
                aiohttp.WSMsgType.ERROR,
            ):
                return

    def _handle_message(self, data: dict[str, Any]) -> None:
        msg_type = data.get("type")
        if msg_type == "state":
            state = data.get("data")
            if isinstance(state, dict):
                self.state = state
                for cb in list(self._state_callbacks):
                    try:
                        cb(state)
                    except Exception:  # noqa: BLE001
                        _LOGGER.exception("Error in state callback")
        elif msg_type == "event":
            name = str(data.get("event"))
            evdata = data.get("data") or {}
            if not isinstance(evdata, dict):
                evdata = {"value": evdata}
            for cb in list(self._event_callbacks):
                try:
                    cb(name, evdata)
                except Exception:  # noqa: BLE001
                    _LOGGER.exception("Error in event callback")
        elif msg_type == "result":
            fut = self._pending.get(data.get("id"))  # type: ignore[arg-type]
            if fut is not None and not fut.done():
                fut.set_result(data)
        elif msg_type == "pong":
            pass
        else:
            _LOGGER.debug("Unknown message from frame: %s", data)


class _WsLost(Exception):
    """Internal: the WebSocket went away while a command was pending."""


async def _safe_json(resp: aiohttp.ClientResponse) -> Any:
    try:
        return await resp.json(content_type=None)
    except (ValueError, aiohttp.ContentTypeError):
        return None
