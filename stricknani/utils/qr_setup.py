"""QR-code setup payloads for Android onboarding (SNA-13).

Mirrors the sibling apps' own device-provisioning QR scheme (nyetbox's
`nix run .#nyetbox-setup` / `QrConfigCodec`): a `stricknani://setup?p=<payload>`
URI, where `<payload>` is a base64url (no padding), unpadded JSON envelope
carrying the server's own base URL and a freshly minted API token. Unlike
nyetbox (a local CLI tool generating the QR offline against a third-party
NetBox server), stricknani's own backend is what's serving the page, so it's
generated server-side here rather than as a separate local script.

The payload is deliberately unencrypted - like nyetbox's, not jollyfin's
password-protected variant - since it's shown once on an already-authenticated
Settings page and scanned immediately, not carried around long-term the way a
device-to-device config export might be.
"""

from __future__ import annotations

import base64
import json
import time
from io import BytesIO

import qrcode
from qrcode.image.pil import PilImage

_SCHEME_VERSION = 1
_QR_BOX_SIZE = 8


def build_setup_uri(base_url: str, token: str) -> str:
    """Build the `stricknani://setup?p=...` URI encoding `base_url` + `token`."""
    envelope = {
        "version": _SCHEME_VERSION,
        "createdAt": int(time.time() * 1000),
        "baseUrl": base_url,
        "token": token,
    }
    payload = base64.urlsafe_b64encode(json.dumps(envelope).encode("utf-8")).rstrip(
        b"="
    )
    return f"stricknani://setup?p={payload.decode('ascii')}"


def request_base_url(scheme: str, host: str) -> str:
    """Build `<scheme>://<host>` for embedding in a setup QR/deep link."""
    return f"{scheme}://{host}"


def render_qr_data_uri(data: str) -> str:
    """Render `data` as a QR code, returned as an inline `data:image/png` URI."""
    img = qrcode.make(data, box_size=_QR_BOX_SIZE, image_factory=PilImage)
    buffer = BytesIO()
    img.save(buffer, format="PNG")
    encoded = base64.b64encode(buffer.getvalue()).decode("ascii")
    return f"data:image/png;base64,{encoded}"
