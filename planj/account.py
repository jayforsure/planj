"""Sign-in without a server: email + password derive the pairing code on the device.

    master = PBKDF2-HMAC-SHA256(password, "planj-account-v1|" + lower(email), 200000 rounds, 32 bytes)
    code   = Crockford base32 of the first 100 bits of master, shown as XXXXX-XXXXX-XXXXX-XXXXX

Every device that signs in with the same credentials computes the same code, and the code
is what the relay mailbox and the encryption key already derive from. Nothing about the
account ever reaches a server. Keep this byte-identical with the phone's AccountKey.java.
"""

import hashlib

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
ROUNDS = 200_000
CODE_CHARS = 20


def normalise_email(email: str) -> str:
    return email.strip().lower()


def pairing_code(email: str, password: str) -> str:
    if not normalise_email(email) or not password:
        raise ValueError("email and password are both needed")
    master = hashlib.pbkdf2_hmac("sha256", password.encode("utf-8"),
                                 ("planj-account-v1|" + normalise_email(email)).encode("utf-8"), ROUNDS, 32)
    bits = int.from_bytes(master[:13], "big") >> (104 - 100)  # the first 100 bits
    chars = "".join(ALPHABET[(bits >> (5 * (CODE_CHARS - 1 - i))) & 31] for i in range(CODE_CHARS))
    return "-".join(chars[i:i + 5] for i in range(0, CODE_CHARS, 5))
