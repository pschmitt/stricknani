"""Markdown rendering utilities."""

import bleach
import markdown as md


def render_markdown(text: str) -> str:
    """
    Render markdown text to sanitized HTML.

    Args:
        text: Markdown text to render

    Returns:
        Sanitized HTML
    """
    # Convert markdown to HTML
    html = md.markdown(text, extensions=["extra", "nl2br"])

    # Sanitize HTML
    allowed_tags = [
        "p",
        "br",
        "strong",
        "em",
        "u",
        "h1",
        "h2",
        "h3",
        "h4",
        "h5",
        "h6",
        "blockquote",
        "code",
        "pre",
        "ul",
        "ol",
        "li",
        "a",
        "img",
    ]
    allowed_attrs = {
        "a": ["href", "title"],
        "img": ["src", "alt", "title"],
    }

    return bleach.clean(html, tags=allowed_tags, attributes=allowed_attrs, strip=True)
