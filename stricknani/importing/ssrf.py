"""SSRF protection for server-side URL fetching.

URL/pattern imports fetch arbitrary user-supplied URLs server-side. Without a
guard this is a Server-Side Request Forgery (SSRF) hole: an attacker could aim
an import at cloud metadata endpoints (``169.254.169.254``), loopback services
(``127.0.0.1``/``localhost``), or hosts on the server's private network.

:func:`validate_public_url` resolves the target hostname and rejects any URL
that resolves to a private, loopback, link-local, reserved, multicast or
unspecified address. It is called before every outbound import request and,
where feasible, again for each redirect hop so that neither the initial URL nor
a redirect target can reach internal infrastructure.

Self-hosters who intentionally import from a LAN source can opt out by setting
``ALLOW_PRIVATE_IMPORT_HOSTS=true`` (secure by default).
"""

from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse

_ALLOWED_SCHEMES = {"http", "https"}


class SSRFError(Exception):
    """Raised when a URL is rejected by the SSRF guard."""


def _is_blocked_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    """Return ``True`` for any address that must not be reached from imports."""
    if (
        ip.is_private
        or ip.is_loopback
        or ip.is_link_local
        or ip.is_reserved
        or ip.is_multicast
        or ip.is_unspecified
    ):
        return True
    # An IPv6 address can embed an IPv4 address (e.g. ``::ffff:169.254.169.254``
    # or ``::ffff:127.0.0.1``); validate the mapped address too.
    mapped = getattr(ip, "ipv4_mapped", None)
    if mapped is not None:
        return _is_blocked_ip(mapped)
    return False


def _allow_private_default() -> bool:
    """Read the opt-out toggle from config lazily to avoid import cycles."""
    from stricknani.config import config

    return config.ALLOW_PRIVATE_IMPORT_HOSTS


def validate_public_url(url: str, *, allow_private: bool | None = None) -> None:
    """Validate that ``url`` is safe to fetch server-side.

    Args:
        url: The URL about to be fetched.
        allow_private: Override the ``ALLOW_PRIVATE_IMPORT_HOSTS`` config toggle.
            When ``None`` (the default) the config value is used.

    Raises:
        SSRFError: If the scheme is not http/https, the host is missing or
            cannot be resolved, or any resolved address is
            private/loopback/link-local/reserved/multicast/unspecified.
    """
    if allow_private is None:
        allow_private = _allow_private_default()

    parsed = urlparse(url)
    scheme = parsed.scheme.lower()
    if scheme not in _ALLOWED_SCHEMES:
        raise SSRFError(f"URL scheme not allowed: {parsed.scheme or '(none)'}")

    host = parsed.hostname
    if not host:
        raise SSRFError("URL has no host")

    if allow_private:
        return

    # If the host is already an IP literal, validate it directly instead of
    # resolving it (getaddrinfo would just echo it back).
    try:
        literal_ip = ipaddress.ip_address(host)
    except ValueError:
        literal_ip = None
    if literal_ip is not None:
        if _is_blocked_ip(literal_ip):
            raise SSRFError(f"URL host resolves to a non-public address: {host}")
        return

    try:
        addr_infos = socket.getaddrinfo(host, None, proto=socket.IPPROTO_TCP)
    except socket.gaierror as exc:
        raise SSRFError(f"Could not resolve host: {host}") from exc

    if not addr_infos:
        raise SSRFError(f"Could not resolve host: {host}")

    for info in addr_infos:
        ip_str = info[4][0]
        try:
            resolved_ip = ipaddress.ip_address(ip_str)
        except ValueError as exc:
            raise SSRFError(f"Invalid resolved address for {host}: {ip_str}") from exc
        if _is_blocked_ip(resolved_ip):
            raise SSRFError(
                f"URL host resolves to a non-public address: {host} -> {ip_str}"
            )


__all__ = ["SSRFError", "validate_public_url"]
