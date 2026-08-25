"""ASGI application entrypoint."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.errors import install_error_handling
from app.core.config import get_settings
from app.core.logging import configure_logging


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """Validate required configuration before serving requests.

    Yields:
        Control to FastAPI after configuration validation succeeds.

    Raises:
        pydantic.ValidationError: If a required environment value is missing or invalid.
    """
    get_settings()
    yield


def create_app() -> FastAPI:
    """Create the FastAPI application.

    Returns:
        Configured FastAPI application.
    """
    configure_logging()
    application = FastAPI(title="길픽 API", version="0.1.0", lifespan=lifespan)
    install_error_handling(application)
    return application


app = create_app()
