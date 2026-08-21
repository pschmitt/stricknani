"""Gauge calculator routes."""

from typing import Annotated

from fastapi import APIRouter, Depends, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse

from stricknani.models import User
from stricknani.routes.auth import get_current_user
from stricknani.utils.gauge import calculate_gauge
from stricknani.web.templating import render_template

router: APIRouter = APIRouter(prefix="/gauge", tags=["gauge"])


@router.get("/", response_class=HTMLResponse)
async def gauge_calculator(
    request: Request,
    current_user: User | None = Depends(get_current_user),
) -> HTMLResponse:
    """Show gauge calculator page."""
    return await render_template(
        "gauge/calculator.html",
        request,
        {
            "current_user": current_user,
        },
    )


@router.post("/calculate")
async def calculate(
    pattern_gauge_stitches: Annotated[int, Form(gt=0)],
    pattern_gauge_rows: Annotated[int, Form(gt=0)],
    user_gauge_stitches: Annotated[int, Form(gt=0)],
    user_gauge_rows: Annotated[int, Form(gt=0)],
    pattern_cast_on_stitches: Annotated[int, Form(gt=0)],
    pattern_row_count: Annotated[int | None, Form(gt=0)] = None,
) -> JSONResponse:
    """Calculate gauge adjustments."""
    result = calculate_gauge(
        pattern_gauge_stitches=pattern_gauge_stitches,
        pattern_gauge_rows=pattern_gauge_rows,
        user_gauge_stitches=user_gauge_stitches,
        user_gauge_rows=user_gauge_rows,
        pattern_cast_on_stitches=pattern_cast_on_stitches,
        pattern_row_count=pattern_row_count,
    )

    return JSONResponse(
        {
            "adjusted_stitches": result.adjusted_stitches,
            "adjusted_rows": result.adjusted_rows,
            "pattern_gauge_stitches": result.pattern_gauge_stitches,
            "pattern_gauge_rows": result.pattern_gauge_rows,
            "user_gauge_stitches": result.user_gauge_stitches,
            "user_gauge_rows": result.user_gauge_rows,
            "pattern_cast_on_stitches": result.pattern_cast_on_stitches,
            "pattern_row_count": result.pattern_row_count,
        }
    )
