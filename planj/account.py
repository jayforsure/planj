"""Account crypto, v2: the relay authenticates you, but only your devices hold the key.

    master   = PBKDF2-HMAC-SHA256(password, "planj-account-v2|" + lower(email), 200000 rounds, 32 bytes)
    auth     = HKDF-SHA256(master, salt "planj-account-v2", info "auth")   -> hex, sent to the relay
    wrap     = HKDF-SHA256(master, salt "planj-account-v2", info "wrap")   -> AES-256-GCM key, never sent

    account_key   = 32 random bytes, made once when the account is created
    pairing code  = Crockford base32 of the first 100 bits of account_key (what the relay
                    mailbox and data key derive from, unchanged from before)
    keybox.pw     = base64( nonce(12) || AES-256-GCM(account_key, key=wrap, aad="planj-keybox-pw") )
    keybox.rc     = same with key = wrap of (recovery code, "planj-recovery-v2|" + email), aad "planj-keybox-rc"

The relay stores a salted hash of `auth` and the keybox blobs. Knowing `auth` lets someone
fetch the keybox, but opening it needs `wrap`, which only the password (or the recovery
code) produces. Keep byte-identical with the phone's AccountCrypto.java.
"""

from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import urllib.error
import urllib.request
from dataclasses import dataclass

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
ROUNDS = 200_000
CODE_CHARS = 20
SALT = b"planj-account-v2"
RELAY_URL = os.environ.get("PLANJ_RELAY_URL", "https://planj-relay-production.up.railway.app")


def normalise_email(email: str) -> str:
    return email.strip().lower()


def _hkdf(ikm: bytes, info: str) -> bytes:
    prk = hmac.new(SALT, ikm, hashlib.sha256).digest()
    return hmac.new(prk, info.encode("ascii") + b"\x01", hashlib.sha256).digest()


def _master(secret: str, salt_prefix: str, email: str) -> bytes:
    e = normalise_email(email)
    if not e or not secret:
        raise ValueError("email and password are both needed")
    return hashlib.pbkdf2_hmac("sha256", secret.encode("utf-8"), (salt_prefix + e).encode("utf-8"), ROUNDS, 32)


def auth_value(email: str, password: str) -> str:
    return _hkdf(_master(password, "planj-account-v2|", email), "auth").hex()


def wrap_key(email: str, password: str) -> bytes:
    return _hkdf(_master(password, "planj-account-v2|", email), "wrap")


def recovery_wrap_key(email: str, recovery_code: str) -> bytes:
    return _hkdf(_master(normalise_code(recovery_code), "planj-recovery-v2|", email), "wrap")


def normalise_code(typed: str) -> str:
    c = typed.upper().replace("-", "").replace(" ", "").replace("I", "1").replace("L", "1").replace("O", "0")
    if len(c) != CODE_CHARS or any(ch not in ALPHABET for ch in c):
        raise ValueError("a recovery code has 20 letters and digits")
    return c


def format_code(chars: str) -> str:
    return "-".join(chars[i:i + 5] for i in range(0, CODE_CHARS, 5))


def code_from_bits(raw: bytes) -> str:
    """Crockford base32 of the first 100 bits, most significant first."""
    bits = int.from_bytes(raw[:13], "big") >> (104 - 100)
    return format_code("".join(ALPHABET[(bits >> (5 * (CODE_CHARS - 1 - i))) & 31] for i in range(CODE_CHARS)))


def new_account_key() -> bytes:
    return secrets.token_bytes(32)


def new_recovery_code() -> str:
    return code_from_bits(secrets.token_bytes(13))


def pairing_code(account_key: bytes) -> str:
    return code_from_bits(account_key)


# ---- AES-GCM without third-party packages -------------------------------
# Python's standard library has no AES, so the keybox uses a tiny pure-Python AES-GCM.
# It only ever handles 32-byte keys, so speed does not matter.

def _xtime(a: int) -> int:
    a <<= 1
    return (a ^ 0x11B) & 0xFF if a & 0x100 else a


def _mul(a: int, b: int) -> int:
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = _xtime(a)
        b >>= 1
    return r


_SBOX = [0] * 256
_INV = [0] * 256


def _init_sbox() -> None:
    def inverse(x: int) -> int:  # x^254 in GF(2^8), which is x^-1 for x != 0
        r, base, e = 1, x, 254
        while e:
            if e & 1:
                r = _mul(r, base)
            base = _mul(base, base)
            e >>= 1
        return r

    for x in range(256):
        inv = inverse(x) if x else 0
        s = inv
        for _ in range(4):
            inv = ((inv << 1) | (inv >> 7)) & 0xFF
            s ^= inv
        _SBOX[x] = s ^ 0x63
        _INV[s ^ 0x63] = x


_init_sbox()


def _expand_key(key: bytes) -> list[list[int]]:
    nk = len(key) // 4
    rounds = nk + 6
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    rcon = 1
    for i in range(nk, 4 * (rounds + 1)):
        t = list(w[i - 1])
        if i % nk == 0:
            t = t[1:] + t[:1]
            t = [_SBOX[b] for b in t]
            t[0] ^= rcon
            rcon = _xtime(rcon)
        elif nk > 6 and i % nk == 4:
            t = [_SBOX[b] for b in t]
        w.append([w[i - nk][j] ^ t[j] for j in range(4)])
    return [sum(w[4 * r:4 * r + 4], []) for r in range(rounds + 1)]


def _encrypt_block(rk: list[list[int]], block: bytes) -> bytes:
    s = [b ^ k for b, k in zip(block, rk[0])]
    for r in range(1, len(rk)):
        s = [_SBOX[b] for b in s]
        s = [s[(i + 4 * (i % 4)) % 16] for i in range(16)]  # shift rows (column-major state)
        if r != len(rk) - 1:
            out = []
            for c in range(4):
                a = s[4 * c:4 * c + 4]
                out += [
                    _mul(a[0], 2) ^ _mul(a[1], 3) ^ a[2] ^ a[3],
                    a[0] ^ _mul(a[1], 2) ^ _mul(a[2], 3) ^ a[3],
                    a[0] ^ a[1] ^ _mul(a[2], 2) ^ _mul(a[3], 3),
                    _mul(a[0], 3) ^ a[1] ^ a[2] ^ _mul(a[3], 2),
                ]
            s = out
        s = [b ^ k for b, k in zip(s, rk[r])]
    return bytes(s)


def _gf128_mul(x: int, y: int) -> int:
    r = 0
    for i in range(128):
        if (y >> (127 - i)) & 1:
            r ^= x
        x = (x >> 1) ^ (0xE1 << 120) if x & 1 else x >> 1
    return r


def _ghash(h: int, aad: bytes, ct: bytes) -> int:
    def blocks(data: bytes):
        for i in range(0, len(data), 16):
            yield data[i:i + 16].ljust(16, b"\0")

    y = 0
    for blk in list(blocks(aad)) + list(blocks(ct)):
        y = _gf128_mul(y ^ int.from_bytes(blk, "big"), h)
    lengths = ((len(aad) * 8) << 64) | (len(ct) * 8)
    return _gf128_mul(y ^ lengths, h)


def _gcm(key: bytes, nonce: bytes, data: bytes, aad: bytes, encrypt: bool) -> bytes:
    rk = _expand_key(key)
    h = int.from_bytes(_encrypt_block(rk, bytes(16)), "big")
    j0 = int.from_bytes(nonce + b"\0\0\0\1", "big")
    if encrypt:
        pt, ct = data, b""
    else:
        ct, tag = data[:-16], data[-16:]
    body = data if encrypt else ct
    out = bytearray()
    for i in range(0, len(body), 16):
        ctr = ((j0 + 1 + i // 16) & ((1 << 32) - 1)) | (j0 & ~((1 << 32) - 1))
        ks = _encrypt_block(rk, ctr.to_bytes(16, "big"))
        out += bytes(a ^ b for a, b in zip(body[i:i + 16], ks))
    ct_final = bytes(out) if encrypt else ct
    s = _ghash(h, aad, ct_final)
    t = (s ^ int.from_bytes(_encrypt_block(rk, j0.to_bytes(16, "big")), "big")).to_bytes(16, "big")
    if encrypt:
        return ct_final + t
    if not hmac.compare_digest(t, tag):
        raise ValueError("wrong password or recovery code")
    return bytes(out)


def wrap(account_key: bytes, key: bytes, aad: str) -> str:
    nonce = secrets.token_bytes(12)
    return base64.b64encode(nonce + _gcm(key, nonce, account_key, aad.encode("ascii"), True)).decode("ascii")


def unwrap(blob: str, key: bytes, aad: str) -> bytes:
    raw = base64.b64decode(blob)
    if len(raw) != 12 + 32 + 16:
        raise ValueError("keybox is damaged")
    return _gcm(key, raw[:12], raw[12:], aad.encode("ascii"), False)


# ---- relay account API ----------------------------------------------------

class AccountError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


@dataclass
class Session:
    email: str
    token: str
    has_keybox: bool


def _call(method: str, path: str, body: dict | None = None, token: str | None = None) -> dict:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(RELAY_URL + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raise AccountError(exc.code, exc.read().decode("utf-8", "replace").strip() or exc.reason) from None
    except urllib.error.URLError as exc:
        raise AccountError(0, f"could not reach the relay: {exc.reason}") from None


def register(email: str, password: str) -> None:
    _call("POST", "/v1/account/register", {"email": normalise_email(email), "auth": auth_value(email, password)})


def resend(email: str) -> None:
    _call("POST", "/v1/account/resend", {"email": normalise_email(email)})


def verify(email: str, code: str, device: str) -> Session:
    out = _call("POST", "/v1/account/verify", {"email": normalise_email(email), "code": code.strip(), "device": device})
    return Session(normalise_email(email), out["token"], bool(out.get("has_keybox")))


def login(email: str, password: str, device: str) -> Session:
    out = _call("POST", "/v1/account/login",
                {"email": normalise_email(email), "auth": auth_value(email, password), "device": device})
    return Session(normalise_email(email), out["token"], bool(out.get("has_keybox")))


def forgot(email: str) -> None:
    _call("POST", "/v1/account/forgot", {"email": normalise_email(email)})


def reset(email: str, code: str, new_password: str, device: str) -> Session:
    out = _call("POST", "/v1/account/reset", {"email": normalise_email(email), "code": code.strip(),
                                              "auth": auth_value(email, new_password), "device": device})
    return Session(normalise_email(email), out["token"], bool(out.get("has_keybox")))


def get_keybox(s: Session) -> dict | None:
    try:
        return _call("GET", "/v1/account/keybox", token=s.token)
    except AccountError as exc:
        if exc.status == 404:
            return None
        raise


def put_keybox(s: Session, pw: str, rc: str) -> None:
    _call("PUT", "/v1/account/keybox", {"pw": pw, "rc": rc}, token=s.token)


def me(s: Session) -> dict:
    return _call("GET", "/v1/account/me", token=s.token)


def logout(s: Session) -> None:
    _call("POST", "/v1/account/logout", token=s.token)


def delete_account(s: Session) -> None:
    _call("DELETE", "/v1/account/me", token=s.token)


def change_password(s: Session, old_password: str, new_password: str, account_key: bytes) -> None:
    _call("POST", "/v1/account/password", {
        "auth": auth_value(s.email, old_password), "new_auth": auth_value(s.email, new_password),
        "pw": wrap(account_key, wrap_key(s.email, new_password), "planj-keybox-pw"),
    }, token=s.token)


# ---- high-level flows shared by the CLI ------------------------------------

def open_keybox(s: Session, password: str) -> bytes | None:
    """The account key, or None when no device has made one yet."""
    kb = get_keybox(s)
    if kb is None:
        return None
    return unwrap(kb["pw"], wrap_key(s.email, password), "planj-keybox-pw")


def open_keybox_with_recovery(s: Session, recovery_code: str) -> bytes:
    kb = get_keybox(s)
    if kb is None or not kb.get("rc"):
        raise AccountError(404, "this account has no recovery code on file")
    return unwrap(kb["rc"], recovery_wrap_key(s.email, recovery_code), "planj-keybox-rc")


def create_keybox(s: Session, password: str, account_key: bytes, recovery_code: str) -> None:
    put_keybox(s,
               wrap(account_key, wrap_key(s.email, password), "planj-keybox-pw"),
               wrap(account_key, recovery_wrap_key(s.email, recovery_code), "planj-keybox-rc"))
