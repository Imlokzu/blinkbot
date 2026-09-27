"""Auth JWKS must not depend on a dead desktop proxy."""
from unittest.mock import MagicMock, patch

import httpx
import pytest

import auth_clerk


def _usable_jwk() -> dict:
    """A real RSA public key: PyJWT >= 2.15 validates keys as it caches the
    set, so a bare {"kid": ...} is rejected before the test can look."""
    import json

    from cryptography.hazmat.primitives.asymmetric import rsa
    from jwt.algorithms import RSAAlgorithm

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048).public_key()
    jwk = json.loads(RSAAlgorithm.to_jwk(key))
    jwk.update({"kid": "test", "use": "sig", "alg": "RS256"})
    return jwk


def test_direct_jwks_fetch_ignores_proxy_environment():
    response = MagicMock()
    response.json.return_value = {"keys": [_usable_jwk()]}
    response.raise_for_status.return_value = None
    client = MagicMock()
    client.__enter__.return_value.get.return_value = response
    with patch.object(auth_clerk.httpx, "Client", return_value=client) as factory:
        auth_clerk._DirectPyJWKClient("https://example.test/.well-known/jwks.json").fetch_data()
    assert factory.call_args.kwargs["trust_env"] is False
    assert factory.call_args.kwargs["follow_redirects"] is True


def test_direct_jwks_fetch_preserves_cache_on_success():
    response = MagicMock()
    response.json.return_value = {"keys": [_usable_jwk()]}
    response.raise_for_status.return_value = None
    client = MagicMock()
    client.__enter__.return_value.get.return_value = response
    jwks = auth_clerk._DirectPyJWKClient("https://example.test/.well-known/jwks.json")
    with patch.object(auth_clerk.httpx, "Client", return_value=client):
        result = jwks.fetch_data()
    assert result["keys"][0]["kid"] == "test"


def test_direct_jwks_fetch_translates_network_error():
    client = MagicMock()
    client.__enter__.return_value.get.side_effect = httpx.ConnectError("connection refused")
    jwks = auth_clerk._DirectPyJWKClient("https://example.test/.well-known/jwks.json")
    with patch.object(auth_clerk.httpx, "Client", return_value=client):
        with pytest.raises(auth_clerk.PyJWKClientConnectionError, match="Fail to fetch data"):
            jwks.fetch_data()
