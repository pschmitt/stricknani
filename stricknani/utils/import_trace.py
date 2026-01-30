"""Helpers for capturing and persisting import debug traces."""

from __future__ import annotations

import json
import traceback
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any
from uuid import uuid4


def _truncate(value: str, limit: int) -> tuple[str, bool]:
    if len(value) <= limit:
        return value, False
    return value[:limit], True


@dataclass
class ImportTrace:
    """Capture structured debug info for an import run."""

    trace_id: str
    path: Path
    max_chars: int
    data: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def create(cls, trace_dir: Path, *, max_chars: int) -> ImportTrace:
        trace_id = uuid4().hex
        trace_dir.mkdir(parents=True, exist_ok=True)
        path = trace_dir / f"{trace_id}.json"
        data: dict[str, Any] = {
            "trace_id": trace_id,
            "created_at": datetime.now(UTC).isoformat(),
            "events": [],
        }
        return cls(trace_id=trace_id, path=path, max_chars=max_chars, data=data)

    def add_event(self, name: str, payload: dict[str, Any]) -> None:
        event = {
            "name": name,
            "at": datetime.now(UTC).isoformat(),
            "payload": payload,
        }
        self.data.setdefault("events", []).append(event)

    def record_text_blob(self, key: str, value: str | None) -> None:
        if value is None:
            return
        truncated, was_truncated = _truncate(value, self.max_chars)
        self.data[key] = {
            "value": truncated,
            "truncated": was_truncated,
            "length": len(value),
        }

    def record_ai_prompt(self, system_prompt: str, user_prompt: str) -> None:
        system_value, system_truncated = _truncate(system_prompt, self.max_chars)
        user_value, user_truncated = _truncate(user_prompt, self.max_chars)
        self.data.setdefault("ai", {})
        self.data["ai"]["prompt"] = {
            "system": {
                "value": system_value,
                "truncated": system_truncated,
                "length": len(system_prompt),
            },
            "user": {
                "value": user_value,
                "truncated": user_truncated,
                "length": len(user_prompt),
            },
        }

    def record_ai_response(self, raw_content: str) -> None:
        response_value, response_truncated = _truncate(raw_content, self.max_chars)
        self.data.setdefault("ai", {})
        self.data["ai"]["response"] = {
            "value": response_value,
            "truncated": response_truncated,
            "length": len(raw_content),
        }

    def record_error(self, label: str, exc: BaseException) -> None:
        self.data.setdefault("errors", [])
        self.data["errors"].append(
            {
                "label": label,
                "error": str(exc),
                "traceback": traceback.format_exc(),
                "at": datetime.now(UTC).isoformat(),
            }
        )

    def save(self) -> None:
        payload = json.dumps(self.data, indent=2, ensure_ascii=True)
        self.path.write_text(payload, encoding="utf-8")
