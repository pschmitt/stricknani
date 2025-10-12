from __future__ import annotations

from fastapi import Cookie, Depends, HTTPException, Request, status
from sqlalchemy.orm import Session

from .database import SessionLocal
from .models import User
from .security import decode_token


def get_db() -> Session:
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def resolve_user(session_token: str | None, db: Session) -> User | None:
    if session_token is None:
        return None

    username = decode_token(session_token)
    if username is None:
        return None

    return db.query(User).filter(User.username == username).first()


def get_current_user(
    request: Request,
    db: Session = Depends(get_db),
    session_token: str | None = Cookie(default=None, alias="session"),
) -> User:
    user = resolve_user(session_token, db)
    if user is None:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
    request.state.user = user
    return user


def authenticate_request(request: Request) -> User | None:
    session_token = request.cookies.get("session")
    with SessionLocal() as db:
        return resolve_user(session_token, db)
