"""ASGI application entrypoint."""

import asyncio
from contextlib import suppress
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi

from app.api.errors import install_error_handling
from app.api.v1.auth import router as auth_router
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.db import create_session_factory
from app.jobs.auth_cleanup import run_auth_cleanup


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """Validate required configuration before serving requests.

    Yields:
        Control to FastAPI after configuration validation succeeds.

    Raises:
        pydantic.ValidationError: If a required environment value is missing or invalid.
    """
    get_settings()
    cleanup_task = asyncio.create_task(run_auth_cleanup(create_session_factory()))
    try:
        yield
    finally:
        cleanup_task.cancel()
        with suppress(asyncio.CancelledError):
            await cleanup_task


def create_app() -> FastAPI:
    """Create the FastAPI application.

    Returns:
        Configured FastAPI application.
    """
    configure_logging()
    application = FastAPI(title="길픽 API", version="0.1.0", lifespan=lifespan)
    install_error_handling(application)
    application.include_router(auth_router, prefix="/api/v1")

    def contract_openapi() -> dict:
        """생성된 문서에서 계약에 없는 framework 기본 422만 제거한다."""
        if application.openapi_schema:
            return application.openapi_schema
        schema = get_openapi(title=application.title, version=application.version, routes=application.routes)
        for path in schema["paths"].values():
            for operation in path.values():
                operation.get("responses", {}).pop("422", None)
        application.openapi_schema = schema
        return schema

    application.openapi = contract_openapi  # type: ignore[method-assign]
    return application


app = create_app()
