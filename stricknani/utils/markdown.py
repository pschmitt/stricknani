"""Markdown rendering utilities."""

import xml.etree.ElementTree as etree

import bleach
import markdown as md
from markdown.extensions import Extension
from markdown.treeprocessors import Treeprocessor


class ImgLightboxTreeprocessor(Treeprocessor):
    """Add lightbox attributes and styling class to images."""

    def __init__(
        self,
        md: md.Markdown,
        group_name: str = "markdown",
        step_info: str | None = None,
    ) -> None:
        super().__init__(md)
        self.group_name = group_name
        self.step_info = step_info

    def run(self, root: etree.Element) -> None:
        for img in root.iter("img"):
            img.set("class", "markdown-inline-image")
            img.set("data-lightbox-group", self.group_name)
            img.set("data-lightbox-src", img.get("src", ""))

            alt = img.get("alt", "")
            if self.step_info:
                alt = f"{alt} ({self.step_info})" if alt else self.step_info
            img.set("data-lightbox-alt", alt)


class ImgLightboxExtension(Extension):
    """Extension to add lightbox attributes to images."""

    def __init__(self, **kwargs: object) -> None:
        self.config = {
            "group_name": ["markdown", "Name of the lightbox group"],
            "step_info": ["", "Step info to append to alt text"],
        }
        super().__init__(**kwargs)

    def extendMarkdown(self, md: md.Markdown) -> None:
        group_name = str(self.getConfig("group_name"))
        step_info = self.getConfig("step_info")
        # Ensure step_info is treated as string or None, not list/object
        step_info_str = str(step_info) if step_info else None

        md.treeprocessors.register(
            ImgLightboxTreeprocessor(md, group_name, step_info_str), "img_lightbox", 15
        )


def render_markdown(
    text: str, lightbox_group: str = "markdown", step_info: str | None = None
) -> str:
    """
    Render markdown text to sanitized HTML.

    Args:
        text: Markdown text to render
        lightbox_group: Name of the lightbox group for images
        step_info: Optional step info to append to image captions

    Returns:
        Sanitized HTML
    """
    # Convert markdown to HTML
    html = md.markdown(
        text,
        extensions=[
            "extra",
            "nl2br",
            ImgLightboxExtension(group_name=lightbox_group, step_info=step_info),
        ],
    )

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
        "img": [
            "src",
            "alt",
            "title",
            "class",
            "data-lightbox-group",
            "data-lightbox-src",
            "data-lightbox-alt",
        ],
    }

    return bleach.clean(html, tags=allowed_tags, attributes=allowed_attrs, strip=True)
