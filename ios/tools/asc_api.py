"""Authenticated App Store Connect client; credentials never enter logs."""
import base64
import json
import os
import time
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.asymmetric.utils import decode_dss_signature


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def make_token():
    key = serialization.load_pem_private_key(
        base64.b64decode(os.environ["AC_API_KEY_P8"]), password=None)
    now = int(time.time())
    header = {"alg": "ES256", "kid": os.environ["AC_API_KEY_ID"], "typ": "JWT"}
    payload = {"iss": os.environ["AC_API_ISSUER_ID"], "iat": now,
               "exp": now + 1200, "aud": "appstoreconnect-v1"}
    message = b64url(json.dumps(header).encode()) + "." + b64url(json.dumps(payload).encode())
    r, s = decode_dss_signature(key.sign(message.encode(), ec.ECDSA(hashes.SHA256())))
    return message + "." + b64url(r.to_bytes(32, "big") + s.to_bytes(32, "big"))


def api(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request("https://api.appstoreconnect.apple.com" + path,
                                 method=method, data=data,
                                 headers={"Authorization": "Bearer " + make_token(),
                                          "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            raw = resp.read()
            return json.loads(raw) if raw.strip() else {}
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: "
                           + error.read().decode()[:2000]) from None
