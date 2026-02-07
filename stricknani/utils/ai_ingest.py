"""CLI-first AI ingestion utilities.

This module provides a structured-output LLM pipeline that can ingest knitting
patterns from various sources (URL / text / file) and return data that conforms
to a JSON schema (typically derived from the Project/Yarn models).
"""

from __future__ import annotations

import base64
import inspect
import json
import logging
import mimetypes
import os
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Literal, cast

import httpx
from bs4 import BeautifulSoup
from sqlalchemy import Boolean, Integer, String, Text

from stricknani.models import Project, ProjectCategory, Yarn
from stricknani.utils.ai_importer import (
    IMPORT_HEADERS,
    AIPatternImporter,
    _extract_garnstudio_text,
    _is_garnstudio_url,
)

logger = logging.getLogger("stricknani.ai_ingest")

AIIngestTarget = Literal["project", "yarn"]

RE_IMAGE_SIZE = re.compile(r"[-_]\d+x\d+(?=\.[a-z]{3,4}$)", re.I)


DEFAULT_INSTRUCTIONS = (
    "You extract knitting pattern information into a strict JSON object.\n"
    "Follow the provided JSON Schema exactly:\n"
    "- Use the exact field names from the schema.\n"
    "- Use null for unknown / missing values.\n"
    "- Do not invent data that is not present in the source.\n"
    "- Preserve the original language of the source text in all descriptive fields.\n"
    "- Do not populate 'notes' (always use null). Put relevant content into\n"
    "  'description' or step descriptions instead.\n"
    "- Do not populate 'tags' (always use null).\n"
    "- The 'name' field is the pattern/project title (e.g. 'Gledesspreder'),\n"
    "  NOT the category. Never set 'name' to values like 'Pullover'.\n"
    "- Prefer structured, chronological steps when possible.\n"
    "- If the schema includes a 'yarns' array, extract the yarns used in the\n"
    "  project and populate as many Yarn fields as possible (e.g. brand,\n"
    "  fiber_content, weight_category, recommended_needles, length_meters,\n"
    "  weight_grams, link). Prefer one entry per distinct yarn.\n"
    "- If candidate yarn URLs are provided, use them for Yarn.link. Do not\n"
    "  invent/hallucinate Yarn.link URLs.\n"
    "- When ingesting from images/PDFs: do a careful full-text transcription of\n"
    "  all visible text first (including small print, abbreviations, and\n"
    "  legends), then extract structured fields from that content.\n"
    "- If the source includes charts/diagrams and their legends, add a dedicated\n"
    "  step with step_number=0 titled 'Diagrams' and include the legend and any\n"
    "  symbol meanings there.\n"
    "- Prefer COARSE step granularity: 1 step per logical section.\n"
    "- If the source contains explicit section headers (often ALL CAPS like\n"
    "  'ARMAUSSCHNITTBLENDE:'), use that header as the step title and keep the\n"
    "  entire section as a single step description.\n"
    "- Within a step description, include ALL constraints and tips from the\n"
    "  section (e.g. divisibility requirements, stitch pattern definitions,\n"
    "  measurements, and repetitions like 'other side likewise').\n"
    "- Use Markdown lists inside step descriptions when it helps readability.\n"
    "- Minimize redundancy between summary fields (like description/notes) and steps.\n"
    "- For image_urls: avoid thumbnails / resized variants of a full-resolution\n"
    "  image. Prefer the highest-resolution original. Do not include multiple\n"
    "  size variants of the same image.\n"
)


@dataclass(frozen=True, slots=True)
class URLExtraction:
    text: str
    image_urls: list[str]
    yarn_candidates: list[dict[str, str]]


def _type_with_null(value_type: str, *, nullable: bool) -> str | list[str]:
    if not nullable:
        return value_type
    return [value_type, "null"]


def _model_to_openai_json_schema(
    model_class: type,
    *,
    target: AIIngestTarget,
) -> dict[str, Any]:
    """Build a JSON Schema compatible with OpenAI's strict `json_schema` format.

    Notes:
    - We only mark `name` as required. Everything else is optional/nullable to
      keep ingestion forgiving.
    - We disallow additional properties to make the output stable for downstream
      processing.
    """
    from sqlalchemy.orm import ColumnProperty

    properties: dict[str, Any] = {}

    skip_fields = {
        "id",
        "owner_id",
        "owner",
        "created_at",
        "updated_at",
        "images",
        "attachments",
        "steps",
        "yarns",
        "projects",
        "photos",
        "favorited_by",
        "link_archive",
        "link_archive_requested_at",
        "link_archive_failed",
        "is_ai_enhanced",
    }

    for name, _annotation in inspect.get_annotations(model_class).items():
        if name in skip_fields:
            continue

        if not hasattr(model_class, name):
            continue

        col = getattr(model_class, name)
        if not hasattr(col, "property") or not isinstance(col.property, ColumnProperty):
            continue

        columns = list(col.property.columns)
        if not columns:
            continue

        column = columns[0]
        column_type = column.type

        json_type = "string"
        if isinstance(column_type, Integer):
            json_type = "integer"
        elif isinstance(column_type, Boolean):
            json_type = "boolean"
        elif isinstance(column_type, (String, Text)):
            json_type = "string"

        # Keep ingestion permissive: nullable means value can be null.
        nullable = bool(column.nullable)

        description = f"The {name.replace('_', ' ')}"
        if name == "needles":
            description = "Needle size (e.g. '3.5mm', 'US 6')"
        elif name == "yarn":
            description = "Yarn name and weight"
        elif name == "brand":
            description = "The brand or manufacturer (e.g. 'Drops', 'Garnstudio')"
        elif name == "stitch_sample":
            description = "Gauge swatch information (e.g. 21 sts x 28 rows = 10x10cm)"
        elif name == "category":
            description = "Project category"
        elif name == "name":
            description = "The project/pattern name"

        # OpenAI strict JSON schema requires `required` to include every property key.
        # So we model optionality via `null` instead of missing keys.
        ai_nullable = True
        if name == "name":
            # Keep name non-null to avoid "successful" but unusable ingests.
            ai_nullable = False

        prop: dict[str, Any] = {"description": description}
        prop["type"] = _type_with_null(json_type, nullable=ai_nullable or nullable)

        if target == "project" and name == "category":
            prop["enum"] = [c.value for c in ProjectCategory]
            if ai_nullable or nullable:
                # Enum + null requires anyOf for strict JSON schema.
                prop.pop("type", None)
                prop["anyOf"] = [
                    {"type": "string", "enum": [c.value for c in ProjectCategory]},
                    {"type": "null"},
                ]

        properties[name] = prop

    # Extra fields used by imports (not direct columns).
    properties["image_urls"] = {
        "type": ["array", "null"],
        "description": "Relevant high-quality image URLs for this pattern/yarn.",
        "items": {"type": "string"},
    }

    if target == "project":
        properties["steps"] = {
            "type": ["array", "null"],
            "description": "Chronological instruction steps.",
            "items": {
                "type": "object",
                "additionalProperties": False,
                "properties": {
                    "step_number": {"type": "integer"},
                    "title": {"type": "string"},
                    "description": {"type": ["string", "null"]},
                },
                # Required must include every property key; represent optional via null.
                "required": ["step_number", "title", "description"],
            },
        }

    schema: dict[str, Any] = {
        "type": "object",
        "additionalProperties": False,
        "properties": properties,
        # OpenAI strict JSON schema requires all properties to be required.
        # Optionality is expressed via nullable types.
        "required": sorted(properties.keys()),
    }

    return schema


def build_schema_for_target(target: AIIngestTarget) -> dict[str, Any]:
    if target == "project":
        schema = _model_to_openai_json_schema(Project, target=target)
        # Add a structured yarn list to allow richer extraction than the legacy
        # free-form `yarn` string on Project.
        yarn_schema = _model_to_openai_json_schema(Yarn, target="yarn")
        schema["properties"]["yarns"] = {
            "type": ["array", "null"],
            "description": (
                "Yarns used in this project, extracted as structured Yarn objects."
            ),
            "items": yarn_schema,
        }
        schema["required"] = sorted(schema["properties"].keys())
        return schema
    return _model_to_openai_json_schema(Yarn, target=target)


def _guess_mime_type(path: Path) -> str:
    guessed, _encoding = mimetypes.guess_type(str(path))
    return guessed or "application/octet-stream"


def _data_url(mime_type: str, raw: bytes) -> str:
    b64 = base64.b64encode(raw).decode("ascii")
    return f"data:{mime_type};base64,{b64}"


def _deduplicate_image_urls(urls: list[str]) -> list[str]:
    """Group similar image URLs and pick the best one (usually highest res)."""
    if not urls:
        return []

    groups: dict[str, list[str]] = {}
    for url in urls:
        cleaned = url.strip()
        if not cleaned:
            continue

        base = RE_IMAGE_SIZE.sub("", cleaned)
        base = re.sub(r"[?&](w|h|width|height|size)=\d+", "", base, flags=re.I)

        groups.setdefault(base, []).append(cleaned)

    deduped: list[str] = []
    for base, versions in groups.items():
        if len(versions) == 1:
            deduped.append(versions[0])
            continue

        if base in versions:
            deduped.append(base)
            continue

        # Prefer URLs that don't look like explicit thumbs.
        def score(u: str) -> tuple[int, int]:
            lower = u.lower()
            thumb_penalty = 0
            if any(token in lower for token in ("thumb", "thumbnail", "small")):
                thumb_penalty = 1
            # Longer often means more "original" markers.
            return (thumb_penalty, -len(u))

        best = sorted(versions, key=score)[0]
        deduped.append(best)

    return deduped


def _norm_key(value: object) -> str:
    if not isinstance(value, str):
        return ""
    return " ".join(value.strip().lower().split())


def _merge_recommended_needles(a: object, b: object) -> str | None:
    parts: list[str] = []
    for value in (a, b):
        if not isinstance(value, str):
            continue
        for token in value.split(","):
            cleaned = token.strip()
            if cleaned:
                parts.append(cleaned)

    seen: set[str] = set()
    uniq: list[str] = []
    for part in parts:
        key = _norm_key(part)
        if not key or key in seen:
            continue
        seen.add(key)
        uniq.append(part)

    if not uniq:
        return None
    return ", ".join(uniq)


def _deduplicate_and_enrich_yarns(
    yarns: list[dict[str, Any]],
    *,
    candidates: list[dict[str, str]] | None,
) -> list[dict[str, Any]]:
    """Merge duplicate yarn entries and try to fill Yarn.link from candidates."""
    candidate_map: dict[str, list[dict[str, str]]] = {}
    if candidates:
        for cand in candidates:
            link = cand.get("link", "").strip()
            if not link:
                continue
            name_key = _norm_key(cand.get("name", ""))
            if name_key:
                candidate_map.setdefault(name_key, []).append(cand)

    merged: list[dict[str, Any]] = []
    index: dict[tuple[str, str], int] = {}

    for yarn in yarns:
        name_key = _norm_key(yarn.get("name"))
        brand_key = _norm_key(yarn.get("brand"))
        if not name_key:
            merged.append(yarn)
            continue

        key = (name_key, brand_key)
        if key in index:
            tgt = merged[index[key]]
        else:
            merged.append(yarn)
            index[key] = len(merged) - 1
            tgt = yarn

        for field, value in yarn.items():
            if field == "recommended_needles":
                tgt[field] = _merge_recommended_needles(tgt.get(field), value)
                continue
            if (
                field == "image_urls"
                and isinstance(tgt.get(field), list)
                and isinstance(value, list)
            ):
                urls = [u for u in tgt.get(field, []) if isinstance(u, str)] + [
                    u for u in value if isinstance(u, str)
                ]
                tgt[field] = _deduplicate_image_urls(urls)[:10]
                continue
            if tgt.get(field) is None or tgt.get(field) == "":
                tgt[field] = value

        if "notes" in tgt:
            tgt["notes"] = None
        if "tags" in tgt:
            tgt["tags"] = None

        if not isinstance(tgt.get("link"), str) or not tgt.get("link"):
            cands = candidate_map.get(name_key, [])
            if len(cands) == 1:
                tgt["link"] = cands[0]["link"]

    # De-dupe exact repeats (same name/brand/link).
    final: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for yarn in merged:
        k = (
            _norm_key(yarn.get("name")),
            _norm_key(yarn.get("brand")),
            _norm_key(yarn.get("link")),
        )
        if k in seen:
            continue
        seen.add(k)
        final.append(yarn)

    return final


def _looks_like_diagram_url(url: str) -> bool:
    lower = url.lower()
    return any(
        token in lower
        for token in (
            "diagram",
            "chart",
            "schema",
            "schem",
            "legend",
            "measure",
            "skizze",
            "proportions",
        )
    )


async def _fetch_image_data_url(url: str, *, timeout_s: int = 15) -> str | None:
    """Fetch an image and return a data: URL for OpenAI input_image."""
    try:
        async with httpx.AsyncClient(
            timeout=timeout_s, follow_redirects=True
        ) as client:
            resp = await client.get(url, headers=IMPORT_HEADERS)
            resp.raise_for_status()
    except Exception:
        return None

    content_type = str(resp.headers.get("content-type") or "").split(";", 1)[0].strip()
    if not content_type.startswith("image/"):
        return None

    raw = resp.content
    # Keep uploads reasonably small.
    if not raw or len(raw) > 2_000_000:
        return None

    return _data_url(content_type, raw)


async def extract_url(url: str, *, timeout_s: int = 30) -> URLExtraction:
    async with httpx.AsyncClient(
        timeout=timeout_s, follow_redirects=True, headers=IMPORT_HEADERS
    ) as client:
        response = await client.get(url)
        response.raise_for_status()

    soup = BeautifulSoup(response.text, "html.parser")
    for script in soup(["script", "style", "nav", "footer", "header"]):
        script.decompose()

    # Title hints help the model avoid confusing category vs. project name.
    title_bits: list[str] = []
    meta_title = soup.find("meta", attrs={"property": "og:title"})
    if meta_title:
        value = meta_title.get("content")
        if isinstance(value, str) and value.strip():
            title_bits.append(value.strip())
    if soup.title and soup.title.string and soup.title.string.strip():
        title_bits.append(soup.title.string.strip())
    h1 = soup.find("h1")
    if h1:
        h1_text = h1.get_text(" ", strip=True)
        if h1_text:
            title_bits.append(h1_text)

    title_bits = [t for i, t in enumerate(title_bits) if t and t not in title_bits[:i]]

    text_content = ""
    if _is_garnstudio_url(url):
        text_content = _extract_garnstudio_text(soup)

    # Trafilatura for general webpages, best-effort.
    if not text_content:
        try:
            import trafilatura

            text_content = (
                trafilatura.extract(
                    response.text,
                    include_comments=False,
                    include_tables=False,
                    no_fallback=False,
                )
                or ""
            )
        except ImportError:
            text_content = ""

    if not text_content:
        text_content = soup.get_text(separator="\n", strip=True)

    if title_bits:
        text_content = (
            "Page title hints:\n"
            + "\n".join(f"- {t}" for t in title_bits)
            + "\n\n"
            + text_content
        )

    if len(text_content) > 12000:
        text_content = text_content[:12000]

    yarn_candidates: list[dict[str, str]] = []
    if _is_garnstudio_url(url) and "pattern.php" in url:
        try:
            from stricknani.utils.importer import GarnstudioPatternImporter

            importer = GarnstudioPatternImporter(url)
            yarn_links = importer._extract_garnstudio_yarn_links(soup)  # noqa: SLF001
            for info in yarn_links.values():
                name = (info.get("name") or "").strip()
                link = (info.get("link") or "").strip()
                image_url = (info.get("image_url") or "").strip()
                if not link:
                    continue
                item: dict[str, str] = {"link": link}
                if name:
                    item["name"] = name
                if image_url:
                    item["image_url"] = image_url
                yarn_candidates.append(item)
        except Exception:
            yarn_candidates = []

    if yarn_candidates:
        lines: list[str] = []
        for cand in yarn_candidates:
            parts: list[str] = []
            if cand.get("name"):
                parts.append(f"name={cand['name']}")
            parts.append(f"link={cand['link']}")
            if cand.get("image_url"):
                parts.append(f"image_url={cand['image_url']}")
            lines.append("- " + ", ".join(parts))
        text_content = (
            text_content
            + "\n\nCandidate yarn URLs (use for structured yarn extraction):\n"
            + "\n".join(lines)
        )

    # Reuse the existing (battle-tested) image extraction heuristics.
    tmp = AIPatternImporter(url)
    images = await tmp._extract_images(soup)  # noqa: SLF001
    images = tmp._deduplicate_image_urls(images)  # noqa: SLF001

    return URLExtraction(
        text=text_content,
        image_urls=images[:30],
        yarn_candidates=yarn_candidates,
    )


def validate_minimally(data: object, schema: dict[str, Any]) -> dict[str, Any]:
    """Minimal validation for CLI workflows.

    OpenAI strict schema should already enforce this, but we keep a small guard
    to fail fast on unexpected outputs.
    """
    if not isinstance(data, dict):
        raise ValueError("AI output is not a JSON object")

    required = schema.get("required") or []
    if isinstance(required, list):
        for key in required:
            if key not in data:
                raise ValueError(f"AI output missing required field: {key}")

    if schema.get("additionalProperties") is False:
        allowed = set((schema.get("properties") or {}).keys())
        extra = set(data.keys()) - allowed
        if extra:
            raise ValueError(f"AI output has unexpected fields: {sorted(extra)}")

    return cast(dict[str, Any], data)


async def ingest_with_openai(
    *,
    target: AIIngestTarget,
    schema: dict[str, Any],
    source_url: str | None = None,
    source_text: str | None = None,
    file_paths: list[Path] | None = None,
    instructions: str,
    model: str,
    temperature: float | None,
    max_output_tokens: int,
) -> dict[str, Any]:
    """Run an LLM extraction with OpenAI structured outputs."""
    api_key = os.getenv("OPENAI_API_KEY")
    if not api_key:
        raise ValueError("OPENAI_API_KEY is not set")

    from openai import AsyncOpenAI, BadRequestError

    content: list[dict[str, Any]] = []

    if source_url:
        extracted = await extract_url(source_url)
        yarn_candidates = extracted.yarn_candidates

        # Best-effort: attach likely diagram/legend images so the model can extract
        # diagram text/legends for step 0.
        diagram_urls = [u for u in extracted.image_urls if _looks_like_diagram_url(u)]
        diagram_data_urls: list[str] = []
        for u in diagram_urls[:3]:
            data_url = await _fetch_image_data_url(u)
            if data_url:
                diagram_data_urls.append(data_url)

        content.append(
            {
                "type": "input_text",
                "text": (
                    "Source: URL\n"
                    f"URL: {source_url}\n\n"
                    "Extracted text:\n"
                    f"{extracted.text}\n\n"
                    "Candidate image URLs:\n"
                    + "\n".join(f"- {u}" for u in extracted.image_urls)
                ),
            }
        )
        for data_url in diagram_data_urls:
            content.append(
                {
                    "type": "input_image",
                    "detail": "high",
                    "image_url": data_url,
                }
            )
    elif source_text is not None:
        yarn_candidates = None
        content.append(
            {
                "type": "input_text",
                "text": f"Source: text\n\n{source_text}",
            }
        )
    elif file_paths:
        yarn_candidates = None
        content.append(
            {
                "type": "input_text",
                "text": (
                    "Source: files\n"
                    "The following files are attached below:\n"
                    + "\n".join(
                        f"- {p.name} ({_guess_mime_type(p)})" for p in file_paths
                    )
                ),
            }
        )
        for path in file_paths:
            raw = path.read_bytes()
            mime_type = _guess_mime_type(path)
            if mime_type.startswith("image/"):
                content.append(
                    {
                        "type": "input_image",
                        "detail": "high",
                        "image_url": _data_url(mime_type, raw),
                    }
                )
            else:
                content.append(
                    {
                        "type": "input_file",
                        "filename": path.name,
                        "file_data": base64.b64encode(raw).decode("ascii"),
                    }
                )
    else:
        raise ValueError("No source provided (url/text/file)")

    client = AsyncOpenAI(api_key=api_key)
    # The OpenAI SDK uses rich union types for `input`/`text`; keep call-sites
    # ergonomic and cast to satisfy strict type checking.
    openai_input = cast(Any, [{"role": "user", "content": content}])
    openai_text = cast(
        Any,
        {
            "format": {
                "type": "json_schema",
                "name": f"stricknani_{target}_ingest",
                "schema": schema,
                "strict": True,
            }
        },
    )

    create_kwargs: dict[str, Any] = {
        "model": model,
        "instructions": instructions,
        "input": openai_input,
        "text": openai_text,
        "max_output_tokens": max_output_tokens,
    }
    if temperature is not None:
        create_kwargs["temperature"] = temperature

    try:
        response = await client.responses.create(**create_kwargs)
    except BadRequestError as exc:
        # Some models (e.g. GPT-5*) reject `temperature`. Retry without it.
        message = str(getattr(exc, "message", "")) or str(exc)
        if temperature is not None and "temperature" in message.lower():
            create_kwargs.pop("temperature", None)
            response = await client.responses.create(**create_kwargs)
        else:
            raise

    raw_text = response.output_text
    if not raw_text:
        raise ValueError("OpenAI returned an empty response")

    try:
        parsed = json.loads(raw_text)
    except json.JSONDecodeError as exc:
        # Most commonly: the model output got cut off due to max_output_tokens.
        # Retry once with a higher limit.
        bumped = max_output_tokens * 2
        # Avoid unbounded growth; callers can still override explicitly.
        if bumped > 16000:
            bumped = 16000
        if bumped == max_output_tokens:
            raise

        retry_kwargs = dict(create_kwargs)
        retry_kwargs["max_output_tokens"] = bumped
        response = await client.responses.create(**retry_kwargs)
        raw_text = response.output_text
        if not raw_text:
            raise ValueError("OpenAI returned an empty response") from exc
        try:
            parsed = json.loads(raw_text)
        except json.JSONDecodeError as exc2:
            raise ValueError(
                "OpenAI returned invalid JSON (possibly truncated). "
                "Try increasing --max-output-tokens."
            ) from exc2

    data = validate_minimally(parsed, schema)
    if source_url and "link" in (schema.get("properties") or {}):
        if not data.get("link"):
            data["link"] = source_url
    if "notes" in (schema.get("properties") or {}):
        data["notes"] = None
    if "tags" in (schema.get("properties") or {}):
        data["tags"] = None
    if "image_urls" in (schema.get("properties") or {}):
        image_urls = data.get("image_urls")
        if isinstance(image_urls, list):
            str_urls = [u for u in image_urls if isinstance(u, str)]
            data["image_urls"] = _deduplicate_image_urls(str_urls)[:10]
    if "yarns" in (schema.get("properties") or {}):
        yarns = data.get("yarns")
        if isinstance(yarns, list):
            normalized: list[dict[str, Any]] = []
            for yarn in yarns:
                if not isinstance(yarn, dict):
                    continue
                if "notes" in yarn:
                    yarn["notes"] = None
                if "tags" in yarn:
                    yarn["tags"] = None
                if "image_urls" in yarn and isinstance(yarn.get("image_urls"), list):
                    y_urls = [u for u in yarn["image_urls"] if isinstance(u, str)]
                    yarn["image_urls"] = _deduplicate_image_urls(y_urls)[:10]
                normalized.append(yarn)
            data["yarns"] = _deduplicate_and_enrich_yarns(
                normalized, candidates=yarn_candidates
            )
    return data
