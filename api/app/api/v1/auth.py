"""Kakao login HTTP endpoints."""

from __future__ import annotations

from collections.abc import AsyncIterator
from urllib.parse import urlencode

import httpx2
from fastapi import APIRouter, Depends, Query, Request
from fastapi.responses import PlainTextResponse, RedirectResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.errors import AppError, success_response
from app.clients.kakao import KakaoClient
from app.core.config import Settings, get_settings
from app.db import create_session_factory, get_session
from app.schemas.auth import (
    AuthTokenData,
    CreateLoginTransactionRequest,
    ErrorEnvelope,
    LoginTicketExchangeRequest,
    LoginTransactionData,
    SuccessEnvelope,
)
from app.services.auth import AuthService, AuthServiceError, exchange_login_ticket

router = APIRouter(prefix="/auth/kakao", tags=["auth"])
NO_CACHE_HEADERS = {"Cache-Control": "no-store", "Pragma": "no-cache"}
CALLBACK_HEADERS = {"Cache-Control": "no-store", "Referrer-Policy": "no-referrer"}


async def _service(settings: Settings = Depends(get_settings)) -> AsyncIterator[AuthService]:
    """요청이 끝날 때 HTTP connection pool을 닫는 인증 service를 제공한다."""
    async with httpx2.AsyncClient(timeout=5.0) as client:
        yield AuthService(create_session_factory(), KakaoClient(settings, client), settings)


@router.post(
    "/transactions",
    status_code=201,
    response_model=SuccessEnvelope[LoginTransactionData],
    responses={
        201: {"headers": {"Cache-Control": {"schema": {"type": "string", "const": "no-store"}}, "Pragma": {"schema": {"type": "string", "const": "no-cache"}}}},
        400: {"model": ErrorEnvelope},
        500: {"model": ErrorEnvelope},
    },
)
async def create_transaction(payload: CreateLoginTransactionRequest, request: Request, service: AuthService = Depends(_service)):
    """기기별 Kakao login transaction을 발급한다."""
    transaction, authorization_url = await service.create_login_transaction(payload.device_id)
    response = success_response(
        request,
        LoginTransactionData(transaction_id=transaction.transaction_id, authorization_url=authorization_url, expires_in=600),
        status_code=201,
    )
    response.headers.update(NO_CACHE_HEADERS)
    return response


@router.get(
    "/callback",
    status_code=302,
    responses={
        302: {
            "description": "Android verified App Link redirect",
            "headers": {
                "Location": {"schema": {"type": "string", "format": "uri"}, "examples": {"success": {"value": "https://app.gilpick.example/auth/kakao/complete#loginTicket=transaction-id.secret"}, "failure": {"value": "https://app.gilpick.example/auth/kakao/complete?error=KAKAO_AUTH_FAILED"}}},
                "Cache-Control": {"schema": {"type": "string", "const": "no-store"}},
                "Referrer-Policy": {"schema": {"type": "string", "const": "no-referrer"}},
            },
        },
        400: {
            "content": {"text/plain": {"schema": {"type": "string", "const": "Invalid callback"}}},
            "headers": {"Cache-Control": {"schema": {"type": "string", "const": "no-store"}}, "Referrer-Policy": {"schema": {"type": "string", "const": "no-referrer"}}},
        },
    },
)
async def kakao_callback(
    service: AuthService = Depends(_service),
    settings: Settings = Depends(get_settings),
    state: str | None = Query(default=None),
    code: str | None = Query(default=None),
    error: str | None = Query(default=None),
    error_description: str | None = Query(default=None),
):
    """Kakao callback을 처리하고 민감 자격은 URI fragment로만 전달한다."""
    if not state or (code is None) == (error is None) or (error_description and not error):
        return PlainTextResponse("Invalid callback", status_code=400, headers=CALLBACK_HEADERS)
    if error:
        public_error = "ACCESS_DENIED" if error == "access_denied" else "KAKAO_AUTH_FAILED"
        try:
            await service.fail_kakao_callback(state=state, error_code=public_error)
        except AuthServiceError:
            return PlainTextResponse("Invalid callback", status_code=400, headers=CALLBACK_HEADERS)
        location = f"{settings.android_app_link_base_url}?{urlencode({'error': public_error})}"
    else:
        try:
            ticket = await service.handle_kakao_callback(state=state, code=code or "")
            location = f"{settings.android_app_link_base_url}#loginTicket={ticket}"
        except AuthServiceError as exc:
            if exc.code == "LOGIN_TRANSACTION_EXPIRED":
                return PlainTextResponse("Invalid callback", status_code=400, headers=CALLBACK_HEADERS)
            location = f"{settings.android_app_link_base_url}?{urlencode({'error': exc.code})}"
    return RedirectResponse(location, status_code=302, headers=CALLBACK_HEADERS)


@router.post(
    "/exchange",
    response_model=SuccessEnvelope[AuthTokenData],
    responses={
        200: {"headers": {"Cache-Control": {"schema": {"type": "string", "const": "no-store"}}, "Pragma": {"schema": {"type": "string", "const": "no-cache"}}}},
        201: {"model": SuccessEnvelope[AuthTokenData], "headers": {"Cache-Control": {"schema": {"type": "string", "const": "no-store"}}, "Pragma": {"schema": {"type": "string", "const": "no-cache"}}}},
        401: {"model": ErrorEnvelope},
        403: {"model": ErrorEnvelope},
        500: {"model": ErrorEnvelope},
    },
)
async def exchange_ticket(payload: LoginTicketExchangeRequest, request: Request, session: AsyncSession = Depends(get_session), settings: Settings = Depends(get_settings)):
    """일회용 ticket을 사용자와 현재 기기 session으로 원자 교환한다."""
    try:
        result = await exchange_login_ticket(session, payload.login_ticket, payload.device_id, settings)
    except AuthServiceError as exc:
        raise AppError(exc.status_code, exc.code, "로그인 자격을 확인할 수 없습니다.", retryable=exc.retryable) from exc
    response = success_response(request, result.as_data(), status_code=201 if result.is_new_user else 200)
    response.headers.update(NO_CACHE_HEADERS)
    return response
