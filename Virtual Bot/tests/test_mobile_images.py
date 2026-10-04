"""Original phone image bytes without SSRF, credential leaks, or live requests."""

import asyncio
import base64
from pathlib import Path
import socket
from types import SimpleNamespace

from fastapi import FastAPI, HTTPException, Request
from fastapi.testclient import TestClient
import httpx
import pytest

import mobile_images


PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGNIqegBAAKsAWn9Yi+6AAAAAElFTkSuQmCC"
)
URL = "https://images.fixture.invalid/original.png?signature=synthetic"
ROUTE = "/api/mobile/images/fetch"


class Chunks(httpx.AsyncByteStream):
    def __init__(self, chunks):
        self.chunks = chunks
        self.reads = 0
        self.closed = False

    async def __aiter__(self):
        for chunk in self.chunks:
            self.reads += 1
            yield chunk

    async def aclose(self):
        self.closed = True


@pytest.fixture
def host(monkeypatch):
    original_client = httpx.AsyncClient
    state = SimpleNamespace(requests=[], resolutions=[], clients=[], streams=[], responses=[],
                            addresses=["93.184.216.34"])

    def resolve(name, port, **kwargs):
        state.resolutions.append((name, port, kwargs))
        return [(socket.AF_INET6 if ":" in ip else socket.AF_INET, socket.SOCK_STREAM,
                 6, "", (ip, port)) for ip in state.addresses]

    def response(status=200, *, media="image/png", chunks=None, **headers):
        stream = Chunks([PNG] if chunks is None else chunks)
        state.streams.append(stream)
        return httpx.Response(status, headers={"content-type": media, **headers}, stream=stream)

    def send(request):
        state.requests.append(request)
        result = state.responses.pop(0) if state.responses else response()
        if isinstance(result, Exception):
            raise result
        return result

    def client(**kwargs):
        state.clients.append(kwargs)
        assert kwargs["trust_env"] is False
        assert kwargs["follow_redirects"] is False
        return original_client(transport=httpx.MockTransport(send), **kwargs)

    async def require_user(request: Request):
        if request.headers.get("authorization") != "Bearer synthetic-device-token":
            raise HTTPException(401, {"code": "mobile_auth_required"})
        return "fixture-owner"

    monkeypatch.setattr(mobile_images.socket, "getaddrinfo", resolve)
    monkeypatch.setattr(mobile_images.httpx, "AsyncClient", client)
    app = FastAPI()
    app.include_router(mobile_images.router(require_user))
    with TestClient(app) as test_client:
        state.get = lambda url=URL, **kwargs: test_client.get(
            ROUTE, params={"url": url}, headers={"Authorization": "Bearer synthetic-device-token",
                                               "Cookie": "session=synthetic-private",
                                               "X-Clerk-Token": "synthetic-clerk", **kwargs})
        state.client = test_client
        state.response = response
        yield state


def test_original_bytes_and_pinned_authenticated_contract(host):
    result = host.get()
    assert result.status_code == 200 and result.content == PNG
    assert result.headers["content-type"] == "image/png"
    assert result.headers["content-length"] == str(len(PNG))
    assert result.headers["cache-control"] == "no-store"
    assert result.headers["x-content-type-options"] == "nosniff"
    upstream = host.requests[0]
    assert str(upstream.url) == "https://93.184.216.34/original.png?signature=synthetic"
    assert upstream.headers["host"] == "images.fixture.invalid"
    assert upstream.extensions["sni_hostname"] == "images.fixture.invalid"
    assert upstream.headers["accept-encoding"] == "identity"
    assert not {"authorization", "x-clerk-token", "cookie", "referer"} & set(upstream.headers)
    assert len(host.resolutions) == 1
    assert host.streams[0].closed


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer invalid"}])
def test_rejected_auth_never_resolves_or_fetches(host, headers):
    result = host.client.get(ROUTE, params={"url": URL}, headers=headers)
    assert result.status_code == 401
    assert host.resolutions == host.requests == []


@pytest.mark.parametrize("url", [
    "file:///etc/passwd", "ftp://example.test/image", "data:image/png;base64,AA==",
    "//example.test/image", "https:///image", "https://" + "user:pass" + "@example.test/image",
    "https://user@example.test/", "https://example.test:0/image", "https://example.test:65536/image",
    "https://example.test/\r\nheader", "https://example.test/ image", "https://example.test\\@localhost/",
    "https://[fe80::1%25en0]/", "http://[bad]/",
])
def test_malformed_and_credential_urls_never_resolve(host, url):
    assert host.get(url).status_code == 400
    assert host.resolutions == host.requests == []


@pytest.mark.parametrize("addresses", [
    [], ["127.0.0.1"], ["10.0.0.1"], ["172.16.0.1"], ["192.168.1.1"],
    ["169.254.169.254"], ["0.0.0.0"], ["100.64.0.1"], ["224.0.0.1"],
    ["::1"], ["fc00::1"], ["fe80::1"], ["ff02::1"], ["::ffff:127.0.0.1"],
    ["2002:7f00:0001::"], ["93.184.216.34", "10.0.0.1"],
])
def test_every_resolved_address_must_be_public(host, addresses):
    host.addresses = addresses
    result = host.get()
    assert result.status_code == 400
    assert result.json()["detail"]["code"] == "unsafe_image_destination"
    assert not host.requests


def test_ipv6_public_pin_and_custom_host_port(host):
    host.addresses = ["2606:4700:4700::1111"]
    assert host.get("https://images.fixture.invalid:8443/image").status_code == 200
    assert str(host.requests[0].url) == "https://[2606:4700:4700::1111]:8443/image"
    assert host.requests[0].headers["host"] == "images.fixture.invalid:8443"


def test_each_redirect_gets_new_dns_pin_and_empty_cookie_jar(host):
    host.responses = [host.response(302, location="/next", **{"set-cookie": "private=secret; Path=/"}),
                      host.response(307, location="https://other.fixture.invalid/final"),
                      host.response()]
    assert host.get().content == PNG
    assert [name for name, _, _ in host.resolutions] == [
        "images.fixture.invalid", "images.fixture.invalid", "other.fixture.invalid"]
    assert len(host.clients) == 3
    assert [r.headers["host"] for r in host.requests] == [
        "images.fixture.invalid", "images.fixture.invalid", "other.fixture.invalid"]
    assert [r.url.path for r in host.requests] == ["/original.png", "/next", "/final"]
    assert all("cookie" not in r.headers and "authorization" not in r.headers for r in host.requests)
    assert all(stream.closed for stream in host.streams)
    assert all(stream.reads == 0 for stream in host.streams[:2])


def test_dns_rebinding_on_redirect_never_reaches_private_address(host, monkeypatch):
    def resolve(name, port, **kwargs):
        return [(socket.AF_INET, socket.SOCK_STREAM, 6, "",
                 ("127.0.0.1" if host.requests else "93.184.216.34", port))]

    monkeypatch.setattr(mobile_images.socket, "getaddrinfo", resolve)
    host.responses = [host.response(302, location="/second")]
    assert host.get().status_code == 400
    assert len(host.requests) == 1
    assert host.requests[0].url.host == "93.184.216.34"


@pytest.mark.parametrize("location,status", [
    ("file:///etc/passwd", 400), ("http://" + "user:pass" + "@example.test/image", 400),
    # HTTPX rejects a control byte while constructing its unused next request.
    ("http://bad/\nnext", 502),
])
def test_invalid_redirect_is_rejected_before_second_request(host, location, status):
    host.responses = [host.response(302, location=location)]
    assert host.get().status_code == status
    assert len(host.requests) == 1


@pytest.mark.parametrize("count,status", [(3, 200), (4, 502)])
def test_at_most_three_redirects(host, count, status):
    host.responses = [host.response(302, location=f"/hop-{i}") for i in range(count)]
    assert host.get().status_code == status
    assert len(host.requests) == 4
    assert all(s.closed for s in host.streams)


@pytest.mark.parametrize("media,body", [
    ("image/png", PNG), ("image/jpeg", b"\xff\xd8\xff\xe0fixture"),
    ("image/gif", b"GIF89afixture"), ("image/gif", b"GIF87afixture"),
    ("image/webp", b"RIFF\x04\x00\x00\x00WEBPfixture"),
    ("image/avif", b"\x00\x00\x00\x14ftypavif\x00\x00\x00\x00mif1"),
    ("image/avif", b"\x00\x00\x00\x14ftypmif1\x00\x00\x00\x00avif"),
])
def test_allowed_raster_bytes_are_never_transcoded(host, media, body):
    host.responses = [host.response(media=media.upper() + "; fixture=yes", chunks=[body])]
    result = host.get()
    assert result.status_code == 200
    assert result.content == body and result.headers["content-type"] == media


@pytest.mark.parametrize("media,body", [
    ("text/html", b"<html>private page</html>"), ("image/svg+xml", b"<svg/>"),
    ("application/octet-stream", PNG), ("", PNG), ("image/png", b"<html>fake image</html>"),
    ("image/jpeg", PNG), ("image/png", b""), ("image/avif", b"\x00\x00\x00\x14ftypmif1\x00\x00\x00\x00heic"),
])
def test_pages_unknown_media_and_mismatched_signatures_are_rejected(host, media, body):
    host.responses = [host.response(media=media, chunks=[body])]
    result = host.get()
    assert result.status_code == 415
    assert result.json()["detail"]["code"] == "unsupported_image_type"
    assert host.streams[0].closed


def test_declared_oversize_rejected_without_reading_body(host):
    host.responses = [host.response(**{"content-length": str(mobile_images.MAX_BYTES + 1)})]
    assert host.get().status_code == 413
    assert host.streams[0].reads == 0 and host.streams[0].closed


@pytest.mark.parametrize("claimed", [None, "1"])
def test_streaming_limit_ignores_absent_or_lying_length(host, monkeypatch, claimed):
    monkeypatch.setattr(mobile_images, "MAX_BYTES", len(PNG))
    host.responses = [host.response(chunks=[PNG, b"extra", b"must not read"],
                                    **({"content-length": claimed} if claimed else {}))]
    assert host.get().status_code == 413
    assert host.streams[0].reads == 2 and host.streams[0].closed


def test_exact_limit_is_allowed(host, monkeypatch):
    monkeypatch.setattr(mobile_images, "MAX_BYTES", len(PNG))
    assert host.get().content == PNG


def test_compressed_transfer_rejected_without_decompression(host):
    host.responses = [host.response(**{"content-encoding": "gzip"})]
    assert host.get().status_code == 415
    assert host.streams[0].reads == 0 and host.streams[0].closed


@pytest.mark.parametrize("length", ["bad", "-1", "999999999999999999999999999999"])
def test_invalid_length_is_bounded_error(host, length):
    host.responses = [host.response(**{"content-length": length})]
    assert host.get().status_code == 502


@pytest.mark.parametrize("status", [204, 302, 401, 404, 500])
def test_upstream_failure_never_returns_page_or_upstream_details(host, status):
    host.responses = [host.response(status, chunks=[b"private upstream details"])]
    result = host.get()
    assert result.status_code == 502
    assert b"private upstream" not in result.content
    assert host.streams[0].reads == 0 and host.streams[0].closed


@pytest.mark.parametrize("failure", [httpx.ConnectError("private destination detail"),
                                      httpx.ReadTimeout("private target URL")])
def test_network_failure_is_generic(host, failure):
    host.responses = [failure]
    result = host.get()
    assert result.status_code == 502
    assert result.json()["detail"] == {"code": "image_fetch_failed"}


def test_dns_failure_is_generic(host, monkeypatch):
    def fail(*args, **kwargs):
        raise socket.gaierror("private dns details")

    monkeypatch.setattr(mobile_images.socket, "getaddrinfo", fail)
    assert host.get().json()["detail"] == {"code": "image_fetch_failed"}
    assert not host.requests


def test_total_deadline_covers_dns(host, monkeypatch):
    async def stalled(url):
        await asyncio.Event().wait()

    monkeypatch.setattr(mobile_images, "_destination", stalled)
    monkeypatch.setattr(mobile_images, "TOTAL_TIMEOUT_S", 0.01)
    assert host.get().status_code == 502
    assert not host.requests


def test_missing_or_oversized_query_never_fetches(host):
    assert host.client.get(ROUTE, headers={"Authorization": "Bearer synthetic-device-token"}).status_code == 422
    assert host.get("https://example.test/" + "x" * 8192).status_code == 422
    assert host.resolutions == host.requests == []


def test_main_registers_route_with_existing_auth_before_static_fallback():
    # Inspect the integration without importing main's configured providers.
    source = (Path(__file__).parents[1] / "main.py").read_text()
    include = "app.include_router(mobile_images.router(_require_user))"
    assert "import mobile_images" in source
    assert source.index(include) < source.index('@app.get("/{asset_path:path}"')
