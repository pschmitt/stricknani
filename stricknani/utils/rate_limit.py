"""In-memory rate limiting for auth endpoints (T69).

A minimal sliding-window attempt limiter keyed by an arbitrary string (e.g.
a client IP or a normalized email address). State lives in a process-local
dict, which fits Stricknani's typical single-process self-hosted deployment.
If the app is ever run with multiple worker processes this should move to a
shared backend (e.g. Redis) instead.
"""

from __future__ import annotations

import time
from collections import defaultdict, deque

_attempts: dict[str, deque[float]] = defaultdict(deque)


def reset_rate_limits() -> None:
    """Clear all recorded attempts.

    Used by tests to avoid state leaking across test cases that share the
    same client IP.
    """
    _attempts.clear()


def _prune(key: str, window_seconds: float, now: float) -> deque[float]:
    bucket = _attempts[key]
    while bucket and now - bucket[0] > window_seconds:
        bucket.popleft()
    return bucket


def is_rate_limited(key: str, max_attempts: int, window_seconds: float) -> bool:
    """Return True if `key` has already reached `max_attempts` within the window.

    This only inspects state; it does not record a new attempt. Callers
    should pair it with `record_attempt` where appropriate (e.g. only on
    failed login attempts, so successful logins don't count against the
    limit).
    """
    now = time.monotonic()
    bucket = _prune(key, window_seconds, now)
    return len(bucket) >= max_attempts


def record_attempt(key: str) -> None:
    """Record an attempt for `key` at the current time."""
    _attempts[key].append(time.monotonic())
