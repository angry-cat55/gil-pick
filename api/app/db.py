"""SQLAlchemy async engine and transaction boundaries."""

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from functools import lru_cache

from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import DeclarativeBase

from app.core.config import get_settings


class Base(DeclarativeBase):
    """Declarative base for all persisted entities."""


@lru_cache
def get_engine() -> AsyncEngine:
    """Create and cache the process-wide async database engine.

    Returns:
        SQLAlchemy engine configured from ``DATABASE_URL``.
    """
    return create_async_engine(get_settings().database_url, pool_pre_ping=True)


def create_session_factory(engine: AsyncEngine | None = None) -> async_sessionmaker[AsyncSession]:
    """Create an async session factory.

    Args:
        engine: Optional engine override used by tests.

    Returns:
        Session factory that does not expire entities on commit.
    """
    return async_sessionmaker(engine or get_engine(), expire_on_commit=False)


@asynccontextmanager
async def transaction_session(
    factory: async_sessionmaker[AsyncSession] | None = None,
) -> AsyncIterator[AsyncSession]:
    """Provide one session with an explicit atomic transaction.

    Args:
        factory: Optional session factory override used by tests.

    Yields:
        Session whose transaction commits on success and rolls back on failure.
    """
    session_factory = factory or create_session_factory()
    async with session_factory() as session:
        async with session.begin():
            yield session


async def get_session() -> AsyncIterator[AsyncSession]:
    """Yield a request-scoped transactional database session.

    Yields:
        Session committed after a successful request and rolled back on error.
    """
    async with transaction_session() as session:
        yield session
