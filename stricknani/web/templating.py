"""Jinja2 templating helpers (shared by routes).

Kept separate from `stricknani/main.py` to avoid tight coupling and import-time
side effects when routes only need rendering utilities.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any

from fastapi import Request
from fastapi.responses import HTMLResponse
from fastapi.templating import Jinja2Templates
from fastapi_csrf_protect.flexible import CsrfProtect as FlexibleCsrfProtect
from itsdangerous import BadData, SignatureExpired, URLSafeTimedSerializer

from stricknani.config import config
from stricknani.utils.i18n import install_i18n, language_context

templates_path = Path(__file__).resolve().parents[1] / "templates"
templates = Jinja2Templates(directory=str(templates_path))
templates.env.add_extension("jinja2.ext.do")
install_i18n(templates.env)

templates.env.globals["sentry_frontend_dsn"] = config.SENTRY_DSN_FRONTEND
templates.env.globals["sentry_frontend_env"] = config.SENTRY_ENVIRONMENT
templates.env.globals["sentry_frontend_traces_sample_rate"] = (
    config.SENTRY_FRONTEND_TRACES_SAMPLE_RATE
)
templates.env.globals["sentry_frontend_enabled"] = bool(
    config.SENTRY_DSN_FRONTEND
) and not (config.DEBUG or config.TESTING)


def category_color_filter(category_name: str | None) -> str:
    """Map category names to colorful Tailwind classes using deterministic hashing."""
    if not category_name or category_name == "None":
        return "badge-ghost"

    hash_val = sum(ord(c) for c in category_name)
    palette: list[tuple[str, str]] = [
        ("blue", "blue"),
        ("indigo", "indigo"),
        ("teal", "teal"),
        ("rose", "rose"),
        ("amber", "amber"),
        ("emerald", "emerald"),
        ("orange", "orange"),
        ("purple", "purple"),
        ("pink", "pink"),
        ("cyan", "cyan"),
        ("violet", "violet"),
    ]

    base, dark = palette[hash_val % len(palette)]
    return (
        f"bg-{base}-200 text-{base}-950 border-{base}-300 "
        f"dark:bg-{dark}-950/60 dark:text-{dark}-200 dark:border-{dark}-700"
    )


templates.env.filters["category_color"] = category_color_filter


def get_language(request: Request) -> str:
    """Resolve UI language from cookies/headers."""
    lang_cookie = request.cookies.get("language")
    if lang_cookie and lang_cookie in config.SUPPORTED_LANGUAGES:
        return lang_cookie

    accept_language = request.headers.get("accept-language", "")
    if accept_language:
        candidates: list[tuple[str, float]] = []
        for part in accept_language.split(","):
            raw = part.strip()
            if not raw:
                continue
            pieces = [segment.strip() for segment in raw.split(";")]
            lang_code = pieces[0].lower()
            if "-" in lang_code:
                lang_code = lang_code.split("-", 1)[0]
            quality = 1.0
            for segment in pieces[1:]:
                if segment.startswith("q="):
                    try:
                        quality = float(segment[2:])
                    except ValueError:
                        quality = 0.0
            candidates.append((lang_code, quality))
        candidates.sort(key=lambda item: item[1], reverse=True)
        for lang_code, _ in candidates:
            if lang_code in config.SUPPORTED_LANGUAGES:
                return lang_code

    if "en" in config.SUPPORTED_LANGUAGES:
        return "en"
    return config.DEFAULT_LANGUAGE


async def render_template(
    template_name: str,
    request: Request,
    context: dict[str, Any] | None = None,
    status_code: int = 200,
) -> HTMLResponse:
    """Render a template with i18n, CSRF tokens, and current user context."""
    if context is None:
        context = {}

    language = get_language(request)

    context["request"] = request
    context["current_language"] = language
    context.setdefault(
        "is_dev_instance",
        request.url.hostname in {"localhost", "127.0.0.1"} or config.DEBUG,
    )
    context.setdefault("auto_reload_enabled", config.AUTO_RELOAD)
    context.setdefault("feature_wayback_enabled", config.FEATURE_WAYBACK_ENABLED)

    csrf = FlexibleCsrfProtect()
    csrf_token: str | None = None
    signed_token = request.cookies.get(csrf._cookie_key)
    should_set_cookie = False
    if signed_token:
        serializer = URLSafeTimedSerializer(
            config.CSRF_SECRET_KEY,
            salt="fastapi-csrf-token",
        )
        try:
            csrf_token = serializer.loads(signed_token, max_age=csrf._max_age)
        except (BadData, SignatureExpired):
            csrf_token = None
            signed_token = None

    if csrf_token is None or signed_token is None:
        csrf_token, signed_token = csrf.generate_csrf_tokens()
        should_set_cookie = True

    context["csrf_token"] = csrf_token

    if "current_user" not in context:
        raise ValueError("render_template requires explicit `current_user` in context")

    current_user = context.get("current_user")
    avatar_url = None
    avatar_thumb = None
    if current_user is not None:
        avatar_url = getattr(current_user, "avatar_url", None)
        avatar_thumb = getattr(current_user, "avatar_thumbnail_url", None)

    context.setdefault("current_user_avatar_url", avatar_url)
    context.setdefault("current_user_avatar_thumbnail", avatar_thumb)

    with language_context(language):
        response = templates.TemplateResponse(
            request=request,
            name=template_name,
            context=context,
            status_code=status_code,
        )

    if should_set_cookie:
        csrf.set_csrf_cookie(signed_token, response)
    return response
