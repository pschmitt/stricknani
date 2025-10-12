from __future__ import annotations

from datetime import timedelta

from itsdangerous import BadSignature, SignatureExpired, URLSafeTimedSerializer
from passlib.context import CryptContext

import os

SECRET_KEY = os.getenv("PROJECT_STUDIO_SECRET", "change-me")
TOKEN_EXPIRATION = timedelta(hours=12)

pwd_context = CryptContext(schemes=["argon2"], deprecated="auto")
serializer = URLSafeTimedSerializer(SECRET_KEY)


def verify_password(plain_password: str, hashed_password: str) -> bool:
    return pwd_context.verify(plain_password, hashed_password)


def get_password_hash(password: str) -> str:
    return pwd_context.hash(password)


def generate_token(subject: str) -> str:
    return serializer.dumps(subject)


def decode_token(token: str) -> str | None:
    try:
        return serializer.loads(token, max_age=int(TOKEN_EXPIRATION.total_seconds()))
    except (SignatureExpired, BadSignature):
        return None
