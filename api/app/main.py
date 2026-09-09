"""ASGI application entrypoint."""

import asyncio
from contextlib import suppress
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.openapi.utils import get_openapi

from app.api.errors import install_error_handling
from app.api.v1.auth import router as auth_router
from app.api.v1.alternatives import router as alternatives_router
from app.api.v1.detections import router as detections_router
from app.api.v1.devices import router as devices_router
from app.api.v1.itinerary import router as itinerary_router
from app.api.v1.notifications import router as notifications_router
from app.api.v1.places import router as places_router
from app.api.v1.progress import item_router as progress_item_router
from app.api.v1.progress import router as progress_router
from app.api.v1.progress import transition_router as progress_transition_router
from app.api.v1.route import router as route_router
from app.api.v1.replacements import router as replacements_router
from app.api.v1.trips import router as trips_router
from app.core.config import get_settings
from app.core.logging import configure_logging
from app.db import create_session_factory
from app.jobs.auth_cleanup import run_auth_cleanup
from app.jobs.variable_detection import run_variable_detection
from app.jobs.notification_cleanup import run_notification_cleanup
from app.jobs.notification_dispatch import run_notification_dispatch


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    """Validate required configuration before serving requests.

    Yields:
        Control to FastAPI after configuration validation succeeds.

    Raises:
        pydantic.ValidationError: If a required environment value is missing or invalid.
    """
    get_settings()
    session_factory = create_session_factory()
    cleanup_task = asyncio.create_task(run_auth_cleanup(session_factory))
    # background job은 worker마다 생성되므로 운영 배포는 uvicorn 단일 worker를 권장한다.
    detection_task = asyncio.create_task(
        run_variable_detection(
            session_factory,
            interval_seconds=get_settings().detection_cycle_seconds,
        )
    )
    settings = get_settings()
    notification_dispatch_task = asyncio.create_task(
        run_notification_dispatch(
            session_factory,
            settings=settings,
            interval_seconds=settings.notification_dispatch_interval_seconds,
        )
    )
    notification_cleanup_task = asyncio.create_task(
        run_notification_cleanup(
            session_factory,
            retention_days=settings.notification_retention_days,
            interval_seconds=settings.notification_cleanup_interval_seconds,
        )
    )
    try:
        yield
    finally:
        cleanup_task.cancel()
        detection_task.cancel()
        notification_dispatch_task.cancel()
        notification_cleanup_task.cancel()
        with suppress(asyncio.CancelledError):
            await cleanup_task
        with suppress(asyncio.CancelledError):
            await detection_task
        with suppress(asyncio.CancelledError):
            await notification_dispatch_task
        with suppress(asyncio.CancelledError):
            await notification_cleanup_task


def create_app() -> FastAPI:
    """Create the FastAPI application.

    Returns:
        Configured FastAPI application.
    """
    configure_logging()
    application = FastAPI(title="길픽 API", version="0.1.0", lifespan=lifespan)
    install_error_handling(application)
    application.include_router(auth_router, prefix="/api/v1")
    application.include_router(alternatives_router, prefix="/api/v1")
    application.include_router(detections_router, prefix="/api/v1")
    application.include_router(devices_router, prefix="/api/v1")
    application.include_router(itinerary_router, prefix="/api/v1")
    application.include_router(notifications_router, prefix="/api/v1")
    application.include_router(places_router, prefix="/api/v1")
    application.include_router(progress_router, prefix="/api/v1")
    application.include_router(progress_item_router, prefix="/api/v1")
    application.include_router(progress_transition_router, prefix="/api/v1")
    application.include_router(route_router, prefix="/api/v1")
    application.include_router(replacements_router, prefix="/api/v1")
    application.include_router(trips_router, prefix="/api/v1")

    def contract_openapi() -> dict:
        """생성된 문서에서 계약에 없는 framework 기본 422만 제거한다."""
        if application.openapi_schema:
            return application.openapi_schema
        schema = get_openapi(title=application.title, version=application.version, routes=application.routes)
        for path in schema["paths"].values():
            for operation in path.values():
                responses = operation.get("responses", {})
                if responses.get("422", {}).get("description") == "Validation Error":
                    responses.pop("422")
        application.openapi_schema = schema
        return schema

    application.openapi = contract_openapi  # type: ignore[method-assign]
    return application


app = create_app()
