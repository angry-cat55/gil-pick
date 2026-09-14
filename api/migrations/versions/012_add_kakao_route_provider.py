"""Kakao 대중교통 경로 provider를 허용한다.

Revision ID: 012_add_kakao_route_provider
Revises: 011_create_notifications
Create Date: 2026-09-14
"""

from collections.abc import Sequence

from alembic import op

revision: str = "012_add_kakao_route_provider"
down_revision: str | None = "011_create_notifications"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """기존 ODsay 데이터와 신규 Kakao provider를 함께 허용한다."""
    op.drop_constraint("ck_routes_provider", "routes", type_="check")
    op.create_check_constraint(
        "ck_routes_provider",
        "routes",
        "provider IS NULL OR provider IN ('TMAP', 'ODSAY', 'KAKAO', 'MIXED')",
    )
    op.drop_constraint(
        "ck_progress_segments_provider", "progress_segments", type_="check"
    )
    op.create_check_constraint(
        "ck_progress_segments_provider",
        "progress_segments",
        "provider IN ('TMAP', 'ODSAY', 'KAKAO')",
    )


def downgrade() -> None:
    """Kakao 데이터가 없을 때 이전 provider 제약조건으로 복원한다."""
    op.drop_constraint(
        "ck_progress_segments_provider", "progress_segments", type_="check"
    )
    op.create_check_constraint(
        "ck_progress_segments_provider",
        "progress_segments",
        "provider IN ('TMAP', 'ODSAY')",
    )
    op.drop_constraint("ck_routes_provider", "routes", type_="check")
    op.create_check_constraint(
        "ck_routes_provider",
        "routes",
        "provider IS NULL OR provider IN ('TMAP', 'ODSAY', 'MIXED')",
    )
