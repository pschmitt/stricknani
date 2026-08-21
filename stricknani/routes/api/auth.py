"""Password-based token minting for onboarding (SNA-13).

This is the Android app's alternative to manually pasting a PAT copied from
the web Settings page: trade an email/password for a freshly minted, normal
`ApiToken` (same row shape/hash scheme as `/user/api-tokens`'s "Create token"
button). The raw password is used only for this one verification and is
never persisted.

Unlike every other route under `/api/v1`, this one has no Bearer token yet
(that's the whole point), so it can't rely on that header for CSRF exemption
- see `_CSRF_EXEMPT_PATHS` in `stricknani/main.py`.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from stricknani.config import config
from stricknani.database import get_db
from stricknani.models import ApiToken
from stricknani.routes.api.schemas import TokenMintRequest, TokenMintResponse
from stricknani.utils.auth import authenticate_user, generate_api_token
from stricknani.utils.rate_limit import is_rate_limited, record_attempt

router: APIRouter = APIRouter(prefix="/auth", tags=["api-auth"])


def _client_ip(request: Request) -> str:
    """Best-effort client IP for rate-limit keying (mirrors `routes.auth`'s helper)."""
    return request.client.host if request.client else "unknown"


@router.post("/token", response_model=TokenMintResponse)
async def mint_token(
    request: Request,
    payload: TokenMintRequest,
    db: AsyncSession = Depends(get_db),
) -> TokenMintResponse:
    """Verify email/password, then mint and return a new PAT."""
    ip_key = f"api-token-mint:ip:{_client_ip(request)}"
    email_key = f"api-token-mint:email:{payload.email.strip().lower()}"
    if is_rate_limited(
        ip_key,
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS,
        config.RATE_LIMIT_LOGIN_WINDOW_SECONDS,
    ) or is_rate_limited(
        email_key,
        config.RATE_LIMIT_LOGIN_MAX_ATTEMPTS,
        config.RATE_LIMIT_LOGIN_WINDOW_SECONDS,
    ):
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail="Too many attempts. Please try again later.",
            headers={"Retry-After": str(config.RATE_LIMIT_LOGIN_WINDOW_SECONDS)},
        )

    user = await authenticate_user(db, payload.email, payload.password)
    if not user:
        record_attempt(ip_key)
        record_attempt(email_key)
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid email or password",
        )

    raw_token, token_hash = generate_api_token()
    display_name = payload.token_name.strip() or "Android (password login)"
    db.add(ApiToken(user_id=user.id, name=display_name, token_hash=token_hash))
    await db.commit()

    return TokenMintResponse(token=raw_token)
