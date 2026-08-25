"""Shared FastAPI request dependencies."""

from typing import Annotated

from fastapi import Depends, HTTPException, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.core.config import Settings, get_settings
from app.core.security import AccessTokenError, AuthPrincipal, decode_access_token

bearer_scheme = HTTPBearer(auto_error=False)


def get_current_principal(
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer_scheme)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AuthPrincipal:
    """Authenticate a protected request before its route handler runs.

    Args:
        credentials: Optional HTTP Bearer credentials parsed by FastAPI.
        settings: Validated JWT verification settings.

    Returns:
        Trusted identifiers from the verified Access Token.

    Raises:
        HTTPException: If credentials are missing or the Access Token is invalid.
    """
    if credentials is None or credentials.scheme.lower() != "bearer":
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="INVALID_ACCESS_TOKEN",
            headers={"WWW-Authenticate": "Bearer"},
        )
    try:
        return decode_access_token(credentials.credentials, settings)
    except AccessTokenError as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="INVALID_ACCESS_TOKEN",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc
