import pytest

from planj import account

# Shared with the phone's AccountKey: both sides must produce exactly this.
VECTOR = ("Someone@Example.com", "correct horse battery staple", "R1NF8-F6W0Y-92M6D-P32S1")


def test_same_credentials_same_code_and_email_case_does_not_matter():
    a = account.pairing_code("me@example.com", "pw")
    b = account.pairing_code("  ME@Example.COM ", "pw")
    assert a == b
    assert len(a) == 23 and a.count("-") == 3
    assert all(ch in account.ALPHABET for ch in a.replace("-", ""))


def test_different_password_different_code():
    assert account.pairing_code("me@example.com", "pw") != account.pairing_code("me@example.com", "pw2")


def test_blank_credentials_rejected():
    with pytest.raises(ValueError):
        account.pairing_code("", "pw")
    with pytest.raises(ValueError):
        account.pairing_code("me@example.com", "")


def test_vector_matches_the_phone():
    assert account.pairing_code(VECTOR[0], VECTOR[1]) == VECTOR[2]
