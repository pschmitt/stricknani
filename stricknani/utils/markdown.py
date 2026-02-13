"""Markdown rendering utilities."""

import re
import xml.etree.ElementTree as etree

import markdown as md
import nh3
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
            # Always enforce base styling class.
            img.set("class", "markdown-inline-image")
            img.set("data-lightbox-group", self.group_name)
            img.set("data-lightbox-src", img.get("src", ""))

            # Support our WYSIWYG image size marker stored in the markdown image title:
            # ![alt](url "sn:size=md")
            title = img.get("title", "") or ""
            m = re.search(r"\bsn:size=(sm|md|lg|xl)\b", title)
            if m:
                img.set("data-sn-size", m.group(1))
                cleaned = re.sub(r"\bsn:size=(sm|md|lg|xl)\b", "", title)
                cleaned = re.sub(r"\s+", " ", cleaned).strip()
                if cleaned:
                    img.set("title", cleaned)
                else:
                    img.attrib.pop("title", None)

            # Support a small subset of Pandoc's link_attributes syntax for images:
            # ![alt](url){.sn-size-md}
            # We implement it post-render by looking at the tail text after <img>.
            tail = img.tail or ""
            tail_match = re.match(r"^\s*\{([^}]*)\}\s*$", tail)
            if tail_match:
                attrs_blob = tail_match.group(1)
                classes: list[str] = []
                token_re = (
                    r"(?:\.[A-Za-z0-9_-]+)"
                    r"|(?:#[A-Za-z0-9_-]+)"
                    r"|(?:[A-Za-z0-9_-]+=\"[^\"]*\")"
                    r"|(?:[A-Za-z0-9_-]+=[^\s]+)"
                )
                for token in re.findall(token_re, attrs_blob):
                    token = token.strip()
                    if not token:
                        continue
                    if token.startswith("."):
                        classes.append(token[1:])

                # Map `.sn-size-{sm,md,lg,xl}` to `data-sn-size`.
                for cls in classes:
                    m_cls = re.fullmatch(r"sn-size-(sm|md|lg|xl)", cls)
                    if m_cls:
                        img.set("data-sn-size", m_cls.group(1))
                        break

                # Preserve other classes (sanitizer already allows `class` on img).
                if classes:
                    existing = img.get("class", "") or ""
                    extra = " ".join(
                        [c for c in classes if not c.startswith("sn-size-")]
                    )
                    if extra:
                        img.set("class", f"{existing} {extra}".strip())

                # Remove attribute blob from rendered output.
                img.tail = ""

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
    allowed_tags = {
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
    }
    allowed_attrs = {
        "a": {"href", "title"},
        "img": {
            "src",
            "alt",
            "title",
            "class",
            "data-sn-size",
            "data-lightbox-group",
            "data-lightbox-src",
            "data-lightbox-alt",
        },
    }

    return nh3.clean(html, tags=allowed_tags, attributes=allowed_attrs, link_rel=None)
