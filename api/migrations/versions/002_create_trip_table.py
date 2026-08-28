"""여행 관리 migration을 준비한다.

Revision ID: 002_create_trip_table
Revises: 001_create_auth_tables
Create Date: 2026-08-27
"""

from collections.abc import Sequence

from alembic import op
import sqlalchemy as sa

revision: str = "002_create_trip_table"
down_revision: str | None = "001_create_auth_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """여행 기본정보와 검증 제약을 저장하는 ``trips`` 테이블을 생성한다."""
    op.create_table(
        "trips",
        sa.Column("trip_id", sa.Uuid(), nullable=False),
        sa.Column("user_id", sa.Uuid(), nullable=False),
        sa.Column("name", sa.String(30), nullable=False),
        sa.Column("start_date", sa.Date(), nullable=False),
        sa.Column("end_date", sa.Date(), nullable=False),
        sa.Column(
            "timezone",
            sa.String(40),
            server_default=sa.text("'Asia/Seoul'"),
            nullable=False,
        ),
        sa.Column("version", sa.Integer(), server_default=sa.text("1"), nullable=False),
        sa.Column("deleted_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.CheckConstraint(
            "char_length(name) BETWEEN 2 AND 30",
            name="ck_trips_name_length",
        ),
        sa.CheckConstraint(
            "end_date - start_date BETWEEN 0 AND 6",
            name="ck_trips_date_range",
        ),
        sa.ForeignKeyConstraint(["user_id"], ["users.user_id"], ondelete="CASCADE"),
        sa.PrimaryKeyConstraint("trip_id"),
    )
    op.create_index(
        "ix_trips_user_deleted_at",
        "trips",
        ["user_id", "deleted_at"],
    )
    op.create_index(
        "ix_trips_user_lower_name_active",
        "trips",
        ["user_id", sa.text("lower(name)")],
        postgresql_where=sa.text("deleted_at IS NULL"),
    )


def downgrade() -> None:
    """여행 기본정보 테이블을 제거한다."""
    op.drop_table("trips")
