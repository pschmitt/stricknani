"""Unit checks for the synthetic gzip reverse proxy."""

from __future__ import annotations

import gzip

from ci.stricknani.web_performance_browser import (
    PerformanceFailure,
    _estimate_lab_inp,
    _gzip_response,
    _interaction_groups,
)


def test_gzip_proxy_compresses_eligible_accepted_response() -> None:
    body = b"synthetic catalog entry " * 120

    compressed = _gzip_response("br, gzip", "application/json", body)

    assert compressed is not None
    assert gzip.decompress(compressed) == body
    assert len(compressed) < len(body)


def test_gzip_proxy_compresses_javascript_content_type() -> None:
    body = b"export const fixtureValue = 'synthetic';\n" * 100

    compressed = _gzip_response("gzip", "text/javascript; charset=utf-8", body)

    assert compressed is not None
    assert gzip.decompress(compressed) == body


def test_gzip_proxy_preserves_identity_for_unaccepted_or_small_response() -> None:
    assert _gzip_response("identity", "application/json", b"x" * 2048) is None
    assert _gzip_response("gzip;q=0", "application/json", b"x" * 2048) is None
    assert _gzip_response("gzip", "application/json", b"small") is None
    assert _gzip_response("gzip", "image/jpeg", b"x" * 2048) is None


def test_inp_groups_entries_by_interaction_and_uses_longest_event() -> None:
    entries = [
        {"interaction_id": 7, "duration_ms": 38.2, "event_type": "pointerup"},
        {"interaction_id": 7, "duration_ms": 51.4, "event_type": "click"},
        {"interaction_id": 14, "duration_ms": 22, "event_type": "keydown"},
    ]

    groups = _interaction_groups(entries)

    assert groups == [
        {
            "interaction_id": 7,
            "duration_ms": 51.4,
            "event_count": 2,
            "event_types": {"pointerup", "click"},
        },
        {
            "interaction_id": 14,
            "duration_ms": 22,
            "event_count": 1,
            "event_types": {"keydown"},
        },
    ]


def test_lab_inp_candidate_uses_interaction_count_p98_index() -> None:
    entries = [
        {"interaction_id": item, "duration_ms": duration, "event_type": "click"}
        for item, duration in enumerate((190, 150, 110, 70, 40, 20), start=1)
    ]

    candidate_49 = _estimate_lab_inp(entries, 49)
    candidate_50 = _estimate_lab_inp(entries, 50)
    candidate_100 = _estimate_lab_inp(entries, 100)

    assert candidate_49["lab_inp_candidate_index"] == 0
    assert candidate_49["lab_inp_candidate_ms"] == 190
    assert candidate_50["lab_inp_candidate_index"] == 1
    assert candidate_50["lab_inp_candidate_ms"] == 150
    assert candidate_100["lab_inp_candidate_index"] == 2
    assert candidate_100["lab_inp_candidate_ms"] == 110
    assert candidate_49["observed_interaction_count"] == 6
    assert candidate_49["document_interaction_count"] == 49
    assert not candidate_49["lab_inp_candidate_assessable"]


def test_lab_inp_clamps_p98_index_to_available_candidates() -> None:
    entries = [
        {"interaction_id": item, "duration_ms": duration, "event_type": "click"}
        for item, duration in enumerate((200, 80), start=1)
    ]

    result = _estimate_lab_inp(entries, 500)

    assert result["lab_inp_candidate_index"] == 1
    assert result["lab_inp_candidate_ms"] == 80


def test_lab_inp_assessable_requires_representative_observation_coverage() -> None:
    entries = [
        {"interaction_id": item, "duration_ms": 24, "event_type": "click"}
        for item in range(1, 9)
    ]

    result = _estimate_lab_inp(entries, 16)

    assert result["observed_interaction_coverage"] == 0.5
    assert result["lab_inp_candidate_assessable"]


def test_lab_inp_considers_only_ten_slowest_candidates() -> None:
    entries = [
        {"interaction_id": item, "duration_ms": 220 - item, "event_type": "click"}
        for item in range(1, 21)
    ]

    result = _estimate_lab_inp(entries, 600)

    assert result["lab_inp_candidate_index"] == 9
    assert result["lab_inp_candidate_ms"] == 210
    assert result["observed_interaction_count"] == 20
    assert not result["lab_inp_candidate_assessable"]


def test_lab_inp_rejects_missing_or_invalid_observation_instead_of_zero() -> None:
    entry = {"interaction_id": 1, "duration_ms": 30, "event_type": "click"}

    for entries, count in (([], 0), ([entry], None), ([entry], 0)):
        try:
            _estimate_lab_inp(entries, count)
        except PerformanceFailure:
            pass
        else:
            raise AssertionError("missing INP evidence must not become zero")

    try:
        _interaction_groups(
            [{"interaction_id": 1, "duration_ms": 15, "event_type": "click"}]
        )
    except PerformanceFailure:
        pass
    else:
        raise AssertionError("sub-threshold entries must not become INP candidates")
