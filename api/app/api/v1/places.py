"""장소 검색·상세 endpoint가 공유하는 인증 router."""

from fastapi import APIRouter, Depends

from app.api.dependencies import get_current_principal

router = APIRouter(
    prefix="/places",
    tags=["places"],
    dependencies=[Depends(get_current_principal)],
)
