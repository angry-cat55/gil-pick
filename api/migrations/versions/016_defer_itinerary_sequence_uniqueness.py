"""일정 항목 순서 unique 제약을 transaction commit 시점까지 지연시킨다.

기존 항목을 update로 재정렬할 때(순서 맞바꿈 등) 중간 상태에서 일시적으로
(trip_day_id, sequence) 값이 겹칠 수 있다. 즉시(NOT DEFERRABLE) 제약은 각
UPDATE 문마다 바로 검사해 이 중간 상태에서 실패하므로, transaction commit
시점까지 검사를 미루도록 DEFERRABLE INITIALLY DEFERRED로 바꾼다.

Revision ID: 016_defer_itinerary_sequence
Revises: 015_create_route_estimates
Create Date: 2026-09-16
"""

from collections.abc import Sequence

from alembic import op

revision: str = "016_defer_itinerary_sequence"
down_revision: str | None = "015_create_route_estimates"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    """순서 unique 제약을 DEFERRABLE INITIALLY DEFERRED로 재생성한다."""
    op.drop_constraint(
        "uq_itinerary_items_day_sequence", "itinerary_items", type_="unique"
    )
    op.create_unique_constraint(
        "uq_itinerary_items_day_sequence",
        "itinerary_items",
        ["trip_day_id", "sequence"],
        deferrable=True,
        initially="DEFERRED",
    )


def downgrade() -> None:
    """즉시 검사하는 원래 unique 제약으로 되돌린다."""
    op.drop_constraint(
        "uq_itinerary_items_day_sequence", "itinerary_items", type_="unique"
    )
    op.create_unique_constraint(
        "uq_itinerary_items_day_sequence",
        "itinerary_items",
        ["trip_day_id", "sequence"],
    )
