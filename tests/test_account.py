import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer

import pytest

from planj import account

EMAIL, PASSWORD = "Someone@Example.com", "correct horse battery staple"

# Shared with the phone's AccountCrypto.java: both sides must produce exactly these.
VECTORS = {
    "auth": "16a402d68ef76e004e93e3f17dc2aaf8d412e95a2c58c0b9e8156e0793dc3e35",
    "wrap": "8e35a5eee66ab2e05e168bc370cebd307eb5691c813aabefb4a1dc32b8ae4b81",
    "recovery_wrap": "d12c5496523ef9cfbcd0850781a827b2982fd4749bcdd5d60c7fae2b3b6fa42e",
    "pairing_from_0_31": "000G4-0R40M-30E20-9185G",
}


def test_auth_and_wrap_are_stable_and_distinct():
    a1 = account.auth_value(EMAIL, PASSWORD)
    a2 = account.auth_value("  someone@example.COM ", PASSWORD)
    assert a1 == a2 and len(a1) == 64
    assert account.wrap_key(EMAIL, PASSWORD).hex() != a1
    assert account.auth_value(EMAIL, PASSWORD + "x") != a1


def test_vectors_match_the_phone():
    # Pinned once; AccountCrypto.java carries the same three values.
    assert account.auth_value(EMAIL, PASSWORD) == VECTORS["auth"]
    assert account.wrap_key(EMAIL, PASSWORD).hex() == VECTORS["wrap"]
    assert account.recovery_wrap_key(EMAIL, "ABCDE-FGHJK-MNPQR-STVWX").hex() == VECTORS["recovery_wrap"]
    assert account.pairing_code(bytes(range(32))) == VECTORS["pairing_from_0_31"]


def test_aes_gcm_matches_a_known_answer():
    # NIST GCM test case 14 (256-bit zero key, zero nonce, one zero block).
    key, nonce = bytes(32), bytes(12)
    ct = account._gcm(key, nonce, bytes(16), b"", True)
    assert ct.hex() == "cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919"
    assert account._gcm(key, nonce, ct, b"", False) == bytes(16)


def test_keybox_round_trip_and_wrong_password():
    ak = account.new_account_key()
    blob = account.wrap(ak, account.wrap_key(EMAIL, PASSWORD), "planj-keybox-pw")
    assert account.unwrap(blob, account.wrap_key(EMAIL, PASSWORD), "planj-keybox-pw") == ak
    with pytest.raises(ValueError):
        account.unwrap(blob, account.wrap_key(EMAIL, "wrong"), "planj-keybox-pw")
    with pytest.raises(ValueError):
        account.unwrap(blob, account.wrap_key(EMAIL, PASSWORD), "planj-keybox-rc")


def test_recovery_code_shape_and_normalisation():
    code = account.new_recovery_code()
    assert len(code) == 23 and code.count("-") == 3
    assert account.normalise_code(code.lower().replace("-", " ")) == code.replace("-", "")
    assert account.normalise_code("abcde-fghjk-mnpqr-stvwx".replace("1", "l")) == "ABCDEFGHJKMNPQRSTVWX"
    with pytest.raises(ValueError):
        account.normalise_code("too-short")


class _Relay(BaseHTTPRequestHandler):
    """Just enough of the relay to exercise the client's flows."""
    state = {}

    def log_message(self, *_):
        pass

    def _send(self, status, body=None):
        self.send_response(status)
        self.end_headers()
        if body is not None:
            self.wfile.write(json.dumps(body).encode() if isinstance(body, dict) else body.encode())

    def do_POST(self):
        n = int(self.headers.get("Content-Length", 0))
        req = json.loads(self.rfile.read(n) or b"{}")
        if self.path == "/v1/account/register":
            self.state["auth"] = req["auth"]
            self._send(200, {"ok": True})
        elif self.path == "/v1/account/verify":
            self._send(200 if req["code"] == "123456" else 400, {"token": "t", "has_keybox": "kb" in self.state} if req["code"] == "123456" else "wrong or expired code")
        elif self.path == "/v1/account/login":
            ok = req["auth"] == self.state.get("auth")
            self._send(200 if ok else 401, {"token": "t", "has_keybox": "kb" in self.state} if ok else "wrong email or password")
        else:
            self._send(404, "nope")

    def do_PUT(self):
        n = int(self.headers.get("Content-Length", 0))
        self.state["kb"] = json.loads(self.rfile.read(n))
        self._send(204)

    def do_GET(self):
        if "kb" in self.state:
            self._send(200, self.state["kb"])
        else:
            self._send(404, "no keybox yet")


@pytest.fixture
def relay(monkeypatch):
    _Relay.state = {}
    srv = HTTPServer(("127.0.0.1", 0), _Relay)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    monkeypatch.setattr(account, "RELAY_URL", f"http://127.0.0.1:{srv.server_port}")
    yield srv
    srv.shutdown()


def test_signup_then_signin_on_second_device_yields_same_pairing_code(relay):
    account.register(EMAIL, PASSWORD)
    s = account.verify(EMAIL, "123456", "PC")
    assert not s.has_keybox
    ak, rc = account.new_account_key(), account.new_recovery_code()
    account.create_keybox(s, PASSWORD, ak, rc)

    s2 = account.login(EMAIL, PASSWORD, "Phone")
    assert s2.has_keybox
    assert account.open_keybox(s2, PASSWORD) == ak
    assert account.open_keybox_with_recovery(s2, rc) == ak
    assert account.pairing_code(account.open_keybox(s2, PASSWORD)) == account.pairing_code(ak)

    with pytest.raises(account.AccountError) as exc:
        account.login(EMAIL, "wrong", "Phone")
    assert exc.value.status == 401

