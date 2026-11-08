"""Config flow for the Photo Frame integration."""

from __future__ import annotations

from collections.abc import Mapping
from datetime import timedelta
import logging
import socket
from typing import TYPE_CHECKING, Any

import voluptuous as vol

from homeassistant.auth.const import GROUP_ID_USER
from homeassistant.auth.models import TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
from homeassistant.config_entries import (
    SOURCE_REAUTH,
    ConfigFlow,
    ConfigFlowResult,
    OptionsFlow,
)
from homeassistant.const import CONF_HOST, CONF_NAME, CONF_PORT
from homeassistant.core import HomeAssistant, callback
from homeassistant.helpers import selector
from homeassistant.helpers.aiohttp_client import async_get_clientsession
from homeassistant.helpers.network import NoURLAvailableError, get_url

from .api import (
    FrameAuthError,
    FrameClient,
    FrameCommandError,
    FrameConnectionError,
    FrameError,
    FrameInvalidCodeError,
)
from .const import (
    CONF_CODE,
    CONF_ENTITIES,
    CONF_NOW_PLAYING_ENTITIES,
    CONF_FRAME_ID,
    CONF_HA_ACCESS,
    CONF_HA_REFRESH_TOKEN_ID,
    CONF_HA_URL,
    CONF_HA_USER_ID,
    CONF_PROVISION_VERSION,
    PROVISION_VERSION,
    CONF_TOKEN,
    CONF_WEATHER_ENTITY,
    DEFAULT_NAME,
    DEFAULT_PORT,
    DOMAIN,
    HA_USER_NAME,
    PAIR_CLIENT_NAME,
    QUICK_CONTROL_DOMAINS,
    WIDGET_TOGGLES,
)

if TYPE_CHECKING:
    from .coordinator import PhotoFrameConfigEntry

try:  # HA >= 2025.1
    from homeassistant.helpers.service_info.zeroconf import ZeroconfServiceInfo
except ImportError:  # pragma: no cover
    from homeassistant.components.zeroconf import ZeroconfServiceInfo  # type: ignore[no-redef]

_LOGGER = logging.getLogger(__name__)

CODE_SELECTOR = selector.TextSelector(
    selector.TextSelectorConfig(type=selector.TextSelectorType.TEXT)
)


def _route_ip(host: str, port: int) -> str | None:
    """Local IP of the interface HA uses to reach the frame (no packets sent)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.connect((host, port))
        ip = sock.getsockname()[0]
    return None if ip.startswith(("127.", "0.")) else ip


async def async_frame_ha_urls(
    hass: HomeAssistant, frame_host: str, frame_port: int, override: str | None = None
) -> list[str]:
    """Candidate HA base URLs for the frame, most likely to work first.

    HA's own idea of its URL is often unreachable from the tablet (Docker bridge
    IPs, `homeassistant.local` without mDNS, ...), so the frame gets a list and
    keeps the first one it can connect to.
    """
    urls: list[str] = []
    if override:
        urls.append(override.strip())
    try:
        ip = await hass.async_add_executor_job(_route_ip, frame_host, frame_port)
    except OSError:
        ip = None
    if ip:
        api = hass.config.api
        scheme = "https" if api is not None and api.use_ssl else "http"
        port = api.port if api is not None else 8123
        urls.append(f"{scheme}://{ip}:{port}")
    for kwargs in (
        {"prefer_external": False, "allow_cloud": False},
        {"allow_internal": False, "allow_cloud": False},
        {"allow_internal": False, "prefer_cloud": True},
    ):
        try:
            urls.append(get_url(hass, **kwargs))
        except NoURLAvailableError:
            pass
    seen: set[str] = set()
    out: list[str] = []
    for url in urls:
        url = url.rstrip("/")
        if url and url not in seen:
            seen.add(url)
            out.append(url)
    return out


async def async_provision_ha_access(
    hass: HomeAssistant,
    client: FrameClient,
    frame_name: str,
    override_url: str | None = None,
) -> dict[str, str]:
    """Create/reuse the "Photo Frame" user + LLAT and push it to the frame.

    Returns entry data to store (user id + refresh token id).
    Raises NoURLAvailableError / FrameError on failure.
    """
    urls = await async_frame_ha_urls(hass, client.host, client.port, override_url)
    if not urls:
        raise NoURLAvailableError

    user = None
    for candidate in await hass.auth.async_get_users():
        if (
            candidate.name == HA_USER_NAME
            and not candidate.system_generated
            and not candidate.is_owner
        ):
            user = candidate
            break
    if user is None:
        user = await hass.auth.async_create_user(
            HA_USER_NAME, group_ids=[GROUP_ID_USER], local_only=True
        )
    elif not user.is_active:
        await hass.auth.async_activate_user(user)

    client_name = f"Photo Frame {frame_name}"
    # Only one LLAT per client_name is allowed; replace any previous one.
    for token in list(user.refresh_tokens.values()):
        if (
            token.client_name == client_name
            and token.token_type == TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN
        ):
            hass.auth.async_remove_refresh_token(token)

    refresh_token = await hass.auth.async_create_refresh_token(
        user,
        client_name=client_name,
        token_type=TOKEN_TYPE_LONG_LIVED_ACCESS_TOKEN,
        access_token_expiration=timedelta(days=3650),
    )
    access_token = hass.auth.async_create_access_token(refresh_token)

    try:
        await client.command(
            "provision_ha", {"url": urls[0], "urls": urls, "token": access_token}
        )
    except FrameError:
        hass.auth.async_remove_refresh_token(refresh_token)
        raise

    _LOGGER.debug("Provisioned frame %s with HA urls %s", frame_name, urls)
    return {
        CONF_HA_USER_ID: user.id,
        CONF_HA_REFRESH_TOKEN_ID: refresh_token.id,
        CONF_PROVISION_VERSION: PROVISION_VERSION,
    }


async def async_reprovision(hass: HomeAssistant, entry: PhotoFrameConfigEntry) -> None:
    """Push a fresh token + URL candidates to an already-paired frame."""
    client = entry.runtime_data.client
    old_token_id = entry.data.get(CONF_HA_REFRESH_TOKEN_ID)
    new = await async_provision_ha_access(
        hass, client, entry.title, entry.options.get(CONF_HA_URL) or None
    )
    if (
        old_token_id
        and old_token_id != new[CONF_HA_REFRESH_TOKEN_ID]
        and (old := hass.auth.async_get_refresh_token(old_token_id))
    ):
        hass.auth.async_remove_refresh_token(old)
    hass.config_entries.async_update_entry(entry, data={**entry.data, **new})


class PhotoFrameConfigFlow(ConfigFlow, domain=DOMAIN):
    """Handle a config flow for Photo Frame."""

    VERSION = 1

    def __init__(self) -> None:
        """Initialize."""
        self._host: str | None = None
        self._port: int = DEFAULT_PORT
        self._name: str = DEFAULT_NAME
        self._frame_id: str | None = None
        self._client: FrameClient | None = None

    # ----------------------------------------------------------- discovery

    async def async_step_zeroconf(
        self, discovery_info: ZeroconfServiceInfo
    ) -> ConfigFlowResult:
        """Handle zeroconf discovery."""
        props = discovery_info.properties or {}
        frame_id = props.get("id")
        if not frame_id:
            return self.async_abort(reason="no_id")

        host = discovery_info.host
        port = discovery_info.port or DEFAULT_PORT

        await self.async_set_unique_id(frame_id)
        self._abort_if_unique_id_configured(
            updates={CONF_HOST: host, CONF_PORT: port}
        )

        self._host = host
        self._port = port
        self._frame_id = frame_id
        self._name = (
            props.get("name")
            or discovery_info.name.removesuffix("." + discovery_info.type)
            or DEFAULT_NAME
        )
        self.context["title_placeholders"] = {"name": self._name}
        return await self.async_step_zeroconf_confirm()

    async def async_step_zeroconf_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Confirm discovery before starting pairing."""
        if user_input is not None:
            return await self.async_step_pair()
        self._set_confirm_only()
        return self.async_show_form(
            step_id="zeroconf_confirm",
            description_placeholders={"name": self._name, "host": str(self._host)},
        )

    # -------------------------------------------------------------- manual

    async def async_step_user(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Handle manual setup."""
        errors: dict[str, str] = {}
        if user_input is not None:
            host = user_input[CONF_HOST].strip()
            port = int(user_input[CONF_PORT])
            client = FrameClient(async_get_clientsession(self.hass), host, port)
            try:
                info = await client.get_info()
            except FrameConnectionError:
                errors["base"] = "cannot_connect"
            except FrameError:
                _LOGGER.exception("Unexpected response from %s:%s", host, port)
                errors["base"] = "unknown"
            else:
                await self.async_set_unique_id(info["id"])
                self._abort_if_unique_id_configured(
                    updates={CONF_HOST: host, CONF_PORT: port}
                )
                self._host = host
                self._port = port
                self._frame_id = info["id"]
                self._name = info.get("name") or DEFAULT_NAME
                self.context["title_placeholders"] = {"name": self._name}
                return await self.async_step_pair()

        return self.async_show_form(
            step_id="user",
            data_schema=self.add_suggested_values_to_schema(
                vol.Schema(
                    {
                        vol.Required(CONF_HOST): str,
                        vol.Required(CONF_PORT, default=DEFAULT_PORT): vol.All(
                            vol.Coerce(int), vol.Range(min=1, max=65535)
                        ),
                    }
                ),
                user_input,
            ),
            errors=errors,
        )

    # -------------------------------------------------------------- reauth

    async def async_step_reauth(
        self, entry_data: Mapping[str, Any]
    ) -> ConfigFlowResult:
        """Start re-pairing after the frame rejected our token."""
        self._host = entry_data[CONF_HOST]
        self._port = entry_data[CONF_PORT]
        self._frame_id = entry_data.get(CONF_FRAME_ID)
        self._name = entry_data.get(CONF_NAME) or self._get_reauth_entry().title
        return await self.async_step_reauth_confirm()

    async def async_step_reauth_confirm(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Ask the user to get ready for a new pairing code."""
        if user_input is not None:
            return await self.async_step_pair()
        return self.async_show_form(
            step_id="reauth_confirm",
            description_placeholders={"name": self._name},
        )

    # ------------------------------------------------------------- pairing

    async def async_step_pair(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Show the pairing code form; on first entry ask the frame for a code."""
        errors: dict[str, str] = {}
        assert self._host is not None

        if self._client is None:
            self._client = FrameClient(
                async_get_clientsession(self.hass), self._host, self._port
            )

        if user_input is None:
            try:
                await self._client.pair_start()
            except FrameError as err:
                _LOGGER.debug("pair/start failed: %s", err)
                return self.async_abort(reason="cannot_connect")
        else:
            code = "".join(ch for ch in str(user_input[CONF_CODE]) if ch.isdigit())
            try:
                result = await self._client.pair_finish(code, PAIR_CLIENT_NAME)
            except (FrameInvalidCodeError, FrameAuthError):
                errors[CONF_CODE] = "invalid_code"
            except FrameConnectionError:
                errors["base"] = "cannot_connect"
            except FrameError:
                _LOGGER.exception("Unexpected pairing response")
                errors["base"] = "unknown"
            else:
                return await self._async_finish_pairing(
                    result, bool(user_input.get(CONF_HA_ACCESS, True))
                )

        return self.async_show_form(
            step_id="pair",
            data_schema=vol.Schema(
                {
                    vol.Required(CONF_CODE): CODE_SELECTOR,
                    vol.Required(CONF_HA_ACCESS, default=True): bool,
                }
            ),
            description_placeholders={"name": self._name},
            errors=errors,
        )

    async def _async_finish_pairing(
        self, result: dict[str, Any], ha_access: bool
    ) -> ConfigFlowResult:
        assert self._client is not None and self._host is not None
        token: str = result["token"]
        frame_id = result.get("id") or self._frame_id
        if self._frame_id and frame_id != self._frame_id:
            return self.async_abort(reason="wrong_frame")

        data: dict[str, Any] = {
            CONF_HOST: self._host,
            CONF_PORT: self._port,
            CONF_NAME: self._name,
            CONF_FRAME_ID: frame_id,
            CONF_TOKEN: token,
            CONF_HA_ACCESS: ha_access,
        }

        if ha_access:
            try:
                data.update(
                    await async_provision_ha_access(self.hass, self._client, self._name)
                )
            except NoURLAvailableError:
                _LOGGER.warning(
                    "No local Home Assistant URL configured; frame was not given "
                    "access to Home Assistant"
                )
            except (FrameError, FrameCommandError) as err:
                _LOGGER.warning("Could not provision Home Assistant access: %s", err)

        if self.source == SOURCE_REAUTH:
            entry = self._get_reauth_entry()
            # Revoke the old HA token if a new one replaced it.
            old_token_id = entry.data.get(CONF_HA_REFRESH_TOKEN_ID)
            if (
                old_token_id
                and old_token_id != data.get(CONF_HA_REFRESH_TOKEN_ID)
                and CONF_HA_REFRESH_TOKEN_ID in data
                and (old := self.hass.auth.async_get_refresh_token(old_token_id))
            ):
                self.hass.auth.async_remove_refresh_token(old)
            return self.async_update_reload_and_abort(entry, data_updates=data)

        await self.async_set_unique_id(frame_id, raise_on_progress=False)
        self._abort_if_unique_id_configured(
            updates={CONF_HOST: self._host, CONF_PORT: self._port}
        )
        return self.async_create_entry(title=self._name, data=data)

    # ------------------------------------------------------------- options

    @staticmethod
    @callback
    def async_get_options_flow(config_entry: PhotoFrameConfigEntry) -> OptionsFlow:
        """Return the options flow."""
        return PhotoFrameOptionsFlow()


class PhotoFrameOptionsFlow(OptionsFlow):
    """Widget options; pushed to the frame with `set_widgets`."""

    async def async_step_init(
        self, user_input: dict[str, Any] | None = None
    ) -> ConfigFlowResult:
        """Manage widget options."""
        entry: PhotoFrameConfigEntry = self.config_entry  # type: ignore[assignment]
        errors: dict[str, str] = {}

        if user_input is not None:
            widgets: dict[str, Any] = {
                key: bool(user_input.get(key, False)) for key in WIDGET_TOGGLES
            }
            widgets[CONF_WEATHER_ENTITY] = user_input.get(CONF_WEATHER_ENTITY) or None
            widgets[CONF_ENTITIES] = list(user_input.get(CONF_ENTITIES) or [])
            widgets[CONF_NOW_PLAYING_ENTITIES] = list(
                user_input.get(CONF_NOW_PLAYING_ENTITIES) or []
            )
            ha_url = (user_input.get(CONF_HA_URL) or "").strip().rstrip("/")
            if ha_url and not ha_url.startswith(("http://", "https://")):
                errors[CONF_HA_URL] = "invalid_url"
            else:
                try:
                    await entry.runtime_data.client.command("set_widgets", widgets)
                except AttributeError:
                    errors["base"] = "not_loaded"
                except FrameError as err:
                    _LOGGER.debug("set_widgets failed: %s", err)
                    errors["base"] = "cannot_connect"
                else:
                    options = {**widgets, CONF_HA_URL: ha_url}
                    if ha_url != (entry.options.get(CONF_HA_URL) or "") and entry.data.get(CONF_HA_ACCESS):
                        try:
                            # Options aren't saved yet; reprovision reads them from the entry.
                            self.hass.config_entries.async_update_entry(entry, options=options)
                            await async_reprovision(self.hass, entry)
                        except (FrameError, NoURLAvailableError) as err:
                            _LOGGER.warning("Could not re-provision frame: %s", err)
                            errors["base"] = "cannot_connect"
                    if not errors:
                        return self.async_create_entry(data=options)

        # Defaults: saved options, else what the frame currently reports.
        current: dict[str, Any] = {}
        runtime = getattr(entry, "runtime_data", None)
        if runtime is not None and isinstance(runtime.coordinator.data, dict):
            current = dict(runtime.coordinator.data.get("widgets") or {})
        current.update(entry.options)
        if user_input is not None:
            current.update(user_input)

        schema: dict[Any, Any] = {
            vol.Optional(CONF_WEATHER_ENTITY): selector.EntitySelector(
                selector.EntitySelectorConfig(domain="weather")
            ),
            vol.Optional(CONF_ENTITIES): selector.EntitySelector(
                selector.EntitySelectorConfig(
                    domain=QUICK_CONTROL_DOMAINS, multiple=True
                )
            ),
        }
        schema[vol.Optional(CONF_NOW_PLAYING_ENTITIES)] = selector.EntitySelector(
            selector.EntitySelectorConfig(domain="media_player", multiple=True)
        )
        for key in WIDGET_TOGGLES:
            schema[vol.Optional(key, default=bool(current.get(key, True)))] = bool
        schema[vol.Optional(CONF_HA_URL)] = selector.TextSelector(
            selector.TextSelectorConfig(type=selector.TextSelectorType.URL)
        )

        suggested = {
            CONF_WEATHER_ENTITY: current.get(CONF_WEATHER_ENTITY),
            CONF_ENTITIES: current.get(CONF_ENTITIES) or [],
            CONF_NOW_PLAYING_ENTITIES: current.get(CONF_NOW_PLAYING_ENTITIES) or [],
            CONF_HA_URL: entry.options.get(CONF_HA_URL) or "",
        }
        return self.async_show_form(
            step_id="init",
            data_schema=self.add_suggested_values_to_schema(
                vol.Schema(schema), suggested
            ),
            errors=errors,
        )
