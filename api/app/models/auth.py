"""Authentication entities and database invariants."""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import (
    Boolean,
    CheckConstraint,
    DateTime,
    ForeignKey,
    Index,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class TimestampMixin:
    """Server-timestamp columns shared by mutable authentication entities."""

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), server_default=func.now(), onupdate=func.now(), nullable=False
    )


class User(TimestampMixin, Base):
    """A 길픽 user uniquely identified by a Kakao subject."""

    __tablename__ = "users"
    __table_args__ = (
        UniqueConstraint("social_provider", "social_subject", name="uq_users_social_identity"),
        CheckConstraint("social_provider IN ('KAKAO')", name="ck_users_social_provider"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    social_provider: Mapped[str] = mapped_column(String(20), nullable=False)
    social_subject: Mapped[str] = mapped_column(String(255), nullable=False)
    nickname: Mapped[str | None] = mapped_column(String(80))
    profile_image_url: Mapped[str | None] = mapped_column(Text)
    replacement_suggestion_enabled: Mapped[bool] = mapped_column(
        Boolean, server_default=text("true"), nullable=False
    )
    deleted_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    device_sessions: Mapped[list[DeviceSession]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class DeviceSession(TimestampMixin, Base):
    """One long-lived authentication session for a user and app installation."""

    __tablename__ = "device_sessions"
    __table_args__ = (
        UniqueConstraint("user_id", "client_device_id", name="uq_device_sessions_user_device"),
        CheckConstraint("platform IN ('ANDROID')", name="ck_device_sessions_platform"),
        Index("ix_device_sessions_cleanup", "revoked_at", "refresh_expires_at"),
    )

    session_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    user_id: Mapped[uuid.UUID] = mapped_column(
        ForeignKey("users.user_id", ondelete="CASCADE"), nullable=False
    )
    client_device_id: Mapped[str] = mapped_column(String(255), nullable=False)
    platform: Mapped[str] = mapped_column(String(20), nullable=False)
    refresh_token_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    refresh_expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    fcm_token: Mapped[str | None] = mapped_column(Text)
    app_version: Mapped[str | None] = mapped_column(String(40))
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))

    user: Mapped[User] = relationship(back_populates="device_sessions")


class AuthLoginTransaction(TimestampMixin, Base):
    """Short-lived server state for Kakao authorization and ticket exchange."""

    __tablename__ = "auth_login_transactions"
    __table_args__ = (
        UniqueConstraint("state_hash", name="uq_auth_login_transactions_state_hash"),
        CheckConstraint("platform IN ('ANDROID')", name="ck_auth_login_transactions_platform"),
        CheckConstraint(
            "status IN ('PENDING', 'PROCESSING', 'VERIFIED', 'CONSUMED', 'FAILED', 'EXPIRED')",
            name="ck_auth_login_transactions_status",
        ),
        Index(
            "uq_auth_login_transactions_ticket_hash",
            "login_ticket_hash",
            unique=True,
            postgresql_where=text("login_ticket_hash IS NOT NULL"),
        ),
        Index("ix_auth_login_transactions_cleanup", "status", "expires_at"),
    )

    transaction_id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    state_hash: Mapped[str] = mapped_column(String(64), nullable=False)
    client_device_id: Mapped[str] = mapped_column(String(255), nullable=False)
    platform: Mapped[str] = mapped_column(String(20), nullable=False)
    status: Mapped[str] = mapped_column(String(20), nullable=False)
    login_ticket_hash: Mapped[str | None] = mapped_column(String(64))
    social_subject: Mapped[str | None] = mapped_column(String(255))
    nickname: Mapped[str | None] = mapped_column(String(80))
    profile_image_url: Mapped[str | None] = mapped_column(Text)
    expires_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    ticket_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    consumed_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    failure_code: Mapped[str | None] = mapped_column(String(80))
