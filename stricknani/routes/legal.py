"""Public legal and privacy pages."""

from fastapi import APIRouter, Request
from fastapi.responses import HTMLResponse

from stricknani.web.templating import render_template

router: APIRouter = APIRouter(tags=["legal"])


@router.get("/privacy", response_class=HTMLResponse)
async def privacy_policy(request: Request) -> HTMLResponse:
    """Show the public privacy policy without requiring authentication."""
    return await render_template(
        "legal/privacy.html",
        request,
        {"current_user": None},
    )
