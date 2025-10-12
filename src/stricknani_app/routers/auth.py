from __future__ import annotations

from fastapi import APIRouter, Depends, Form, HTTPException, Response, status
from fastapi.responses import RedirectResponse
from sqlalchemy.orm import Session

from ..dependencies import get_db
from ..models import User
from ..security import generate_token, get_password_hash, verify_password

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/login")
async def login(
    response: Response,
    username: str = Form(...),
    password: str = Form(...),
    db: Session = Depends(get_db),
) -> RedirectResponse:
    user = db.query(User).filter(User.username == username).first()
    if user is None or not verify_password(password, user.hashed_password):
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Invalid credentials")

    token = generate_token(username)
    redirect = RedirectResponse(url="/", status_code=status.HTTP_302_FOUND)
    redirect.set_cookie("session", token, httponly=True, max_age=12 * 3600)
    return redirect


@router.post("/register")
async def register(
    response: Response,
    username: str = Form(...),
    password: str = Form(...),
    db: Session = Depends(get_db),
) -> RedirectResponse:
    if db.query(User).filter(User.username == username).first() is not None:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Username already exists")

    user = User(username=username, hashed_password=get_password_hash(password))
    db.add(user)
    db.commit()

    token = generate_token(username)
    redirect = RedirectResponse(url="/", status_code=status.HTTP_302_FOUND)
    redirect.set_cookie("session", token, httponly=True, max_age=12 * 3600)
    return redirect


@router.get("/logout")
async def logout() -> RedirectResponse:
    redirect = RedirectResponse(url="/auth/login", status_code=status.HTTP_302_FOUND)
    redirect.delete_cookie("session")
    return redirect
