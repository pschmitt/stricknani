"""Gauge calculator routes."""

from typing import Annotated

from fastapi import APIRouter, Depends, Form, Request
from fastapi.responses import HTMLResponse, JSONResponse

from stricknani.main import render_template
from stricknani.models import User
from stricknani.routes.auth import get_current_user
from stricknani.utils.gauge import calculate_gauge

router = APIRouter(prefix="/gauge", tags=["gauge"])


@router.get("/", response_class=HTMLResponse)
async def gauge_calculator_page(
    request: Request,
    current_user: User | None = Depends(get_current_user),
) -> HTMLResponse:
    """Show gauge calculator page."""
    return render_template(
        "gauge/calculator.html",
        request,
        {"current_user": current_user},
    )


@router.post("/calculate")
async def calculate(
    pattern_gauge_stitches: Annotated[int, Form()],
    pattern_gauge_rows: Annotated[int, Form()],
    user_gauge_stitches: Annotated[int, Form()],
    user_gauge_rows: Annotated[int, Form()],
    target_width_cm: Annotated[float, Form()],
    target_height_cm: Annotated[float, Form()] = 0.0,
) -> JSONResponse:
    """Calculate gauge adjustments."""
    result = calculate_gauge(
        pattern_gauge_stitches=pattern_gauge_stitches,
        pattern_gauge_rows=pattern_gauge_rows,
        user_gauge_stitches=user_gauge_stitches,
        user_gauge_rows=user_gauge_rows,
        target_width_cm=target_width_cm,
        target_height_cm=target_height_cm,
    )

    return JSONResponse(
        {
            "adjusted_stitches": result.adjusted_stitches,
            "adjusted_rows": result.adjusted_rows,
            "pattern_gauge_stitches": result.pattern_gauge_stitches,
            "pattern_gauge_rows": result.pattern_gauge_rows,
            "user_gauge_stitches": result.user_gauge_stitches,
            "user_gauge_rows": result.user_gauge_rows,
            "target_width_cm": result.target_width_cm,
            "target_height_cm": result.target_height_cm,
        }
    )
