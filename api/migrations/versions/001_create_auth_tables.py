"""Create authentication foundation tables.

Revision ID: 001_create_auth_tables
Revises:
Create Date: 2026-08-25
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "001_create_auth_tables"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """Create users, device sessions, and login transactions."""
    op.create_table(
        "users",
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("social_provider", sa.String(20), nullable=False),
        sa.Column("social_subject", sa.String(255), nullable=False),
        sa.Column("nickname", sa.String(80), nullable=True),
        sa.Column("profile_image_url", sa.Text(), nullable=True),
        sa.Column(
            "replacement_suggestion_enabled",
            sa.Boolean(),
            server_default=sa.text("true"),
            nullable=False,
        ),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint("social_provider IN ('KAKAO')", name="ck_users_social_provider"),
        sa.PrimaryKeyConstraint("user_id"),
        sa.UniqueConstraint(
            "social_provider", "social_subject", name="uq_users_social_identity"
        ),
    )

    op.create_table(
        "device_sessions",
        sa.Column("session_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("client_device_id", sa.String(255), nullable=False),
        sa.Column("platform", sa.String(20), nullable=False),
        sa.Column("refresh_token_hash", sa.String(64), nullable=False),
        sa.Column("refresh_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("fcm_token", sa.Text(), nullable=True),
        sa.Column("app_version", sa.String(40), nullable=True),
        sa.Column("last_seen_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint("platform IN ('ANDROID')", name="ck_device_sessions_platform"),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("session_id"),
        sa.UniqueConstraint("user_id", "client_device_id", name="uq_device_sessions_user_device"),
    )
    op.create_index(
        "ix_device_sessions_cleanup",
        "device_sessions",
        ["revoked_at", "refresh_expires_at"],
    )

    op.create_table(
        "auth_login_transactions",
        sa.Column("transaction_id", sa.Uuid(), nullable=False),
        sa.Column("state_hash", sa.String(64), nullable=False),
        sa.Column("client_device_id", sa.String(255), nullable=False),
        sa.Column("platform", sa.String(20), nullable=False),
        sa.Column("status", sa.String(20), nullable=False),
        sa.Column("login_ticket_hash", sa.String(64), nullable=True),
        sa.Column("social_subject", sa.String(255), nullable=True),
        sa.Column("nickname", sa.String(80), nullable=True),
        sa.Column("profile_image_url", sa.Text(), nullable=True),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("ticket_expires_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("failure_code", sa.String(80), nullable=True),
        sa.Column(
            "created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.Column(
            "updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False
        ),
        sa.CheckConstraint("platform IN ('ANDROID')", name="ck_auth_login_transactions_platform"),
        sa.CheckConstraint(
            "status IN ('PENDING', 'PROCESSING', 'VERIFIED', 'CONSUMED', 'FAILED', 'EXPIRED')",
            name="ck_auth_login_transactions_status",
        ),
        sa.PrimaryKeyConstraint("transaction_id"),
        sa.UniqueConstraint("state_hash", name="uq_auth_login_transactions_state_hash"),
    )
    op.create_index(
        "uq_auth_login_transactions_ticket_hash",
        "auth_login_transactions",
        ["login_ticket_hash"],
        unique=True,
        postgresql_where=sa.text("login_ticket_hash IS NOT NULL"),
    )
    op.create_index(
        "ix_auth_login_transactions_cleanup",
        "auth_login_transactions",
        ["status", "expires_at"],
    )


def downgrade() -> None:
    """Drop authentication foundation tables in dependency order."""
    op.drop_table("auth_login_transactions")
    op.drop_table("device_sessions")
    op.drop_table("users")
