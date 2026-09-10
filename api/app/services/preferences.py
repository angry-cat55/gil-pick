"""인증 사용자의 단일 알림 설정을 조회·갱신한다."""

import logging
import uuid

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.logging import request_id_context
from app.models.auth import User

logger = logging.getLogger("gilpick.preferences")


class PreferencesService:
    """사용자 설정 행을 request transaction 안에서 처리한다."""

    def __init__(self, session: AsyncSession) -> None:
        self.session = session

    async def get(self, user_id: uuid.UUID) -> bool:
        """인증 사용자의 저장된 장소 변경 제안 알림 설정을 조회한다.

        Args:
            user_id: Access Token에서 검증된 현재 사용자 식별자.

        Returns:
            현재 저장된 알림 설정값.
        """
        return bool(
            await self.session.scalar(
                select(User.replacement_suggestion_enabled).where(
                    User.user_id == user_id,
                    User.deleted_at.is_(None),
                )
            )
        )

    async def update(self, user_id: uuid.UUID, enabled: bool) -> bool:
        """인증 사용자의 알림 설정을 단일 SQL로 원자적으로 갱신한다.

        Args:
            user_id: Access Token에서 검증된 현재 사용자 식별자.
            enabled: 저장할 장소 변경 제안 알림 절대값.

        Returns:
            DB가 마지막으로 성공 처리한 설정값.

        Notes:
            호출자가 소유한 transaction에서 ``UPDATE ... RETURNING`` 한 번만 실행한다.
        """
        result = await self.session.execute(
            update(User)
            .where(User.user_id == user_id, User.deleted_at.is_(None))
            .values(replacement_suggestion_enabled=enabled)
            .returning(User.replacement_suggestion_enabled)
        )
        stored = bool(result.scalar_one())
        logger.info(
            {
                "event": "preference_updated",
                "request_id": request_id_context.get(),
                "result": "SUCCEEDED",
            }
        )
        return stored


__all__ = ["PreferencesService"]
