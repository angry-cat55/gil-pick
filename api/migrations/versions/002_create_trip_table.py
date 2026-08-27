"""Prepare the trip management migration.

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
    """Reserve the F002 revision; T010 adds the ``trips`` table."""


def downgrade() -> None:
    """Revert the empty F002 revision."""
