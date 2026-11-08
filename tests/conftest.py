"""Fixtures for Photo Frame tests (pytest-homeassistant-custom-component)."""

from __future__ import annotations

from collections.abc import AsyncGenerator

from aiohttp import web
import pytest

from .fake_frame import FakeFrame

PAIR_CODE = "123456"


@pytest.fixture(autouse=True)
def auto_enable_custom_integrations(enable_custom_integrations):
    """Enable loading custom_components/ in all tests."""
    return


@pytest.fixture
async def fake_frame(socket_enabled) -> AsyncGenerator[tuple[FakeFrame, int]]:
    """Run the fake frame server on a random localhost port."""
    frame = FakeFrame("Fake Frame", PAIR_CODE)
    runner = web.AppRunner(frame.app())
    await runner.setup()
    site = web.TCPSite(runner, "127.0.0.1", 0)
    await site.start()
    port = runner.addresses[0][1]
    yield frame, port
    frame.cancel_timers()
    for ws in list(frame.clients):
        await ws.close()
    await runner.cleanup()
