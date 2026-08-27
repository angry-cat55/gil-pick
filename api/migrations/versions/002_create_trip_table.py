"""여행 관리 migration을 준비한다.

Revision ID: 002_create_trip_table
Revises: 001_create_auth_tables
Create Date: 2026-08-27
"""

from collections.abc import Sequence

revision: str = "002_create_trip_table"
down_revision: str | None = "001_create_auth_tables"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """F002 revision을 예약하며 T010에서 ``trips`` 테이블을 추가한다."""


def downgrade() -> None:
    """비어 있는 F002 revision을 되돌린다."""
