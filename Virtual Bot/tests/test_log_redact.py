"""Bot tokens never reach the log output."""

from __future__ import annotations

import io
import logging

import log_redact

TOKEN = "1234567890:AAFakeSecretFakeSecretFakeSecret123"


def _logger():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.addFilter(log_redact.RedactTokens())
    logger = logging.getLogger("test_log_redact")
    logger.handlers = [handler]
    logger.propagate = False
    logger.setLevel(logging.INFO)
    return logger, stream


def test_httpx_style_line_is_redacted_and_keeps_the_bot_id():
    logger, stream = _logger()
    logger.info('HTTP Request: %s %s "%s"', "POST", f"https://api.telegram.org/bot{TOKEN}/getUpdates", "HTTP/1.1 200 OK")
    out = stream.getvalue()
    assert "AAFakeSecret" not in out
    assert "bot1234567890:***/getUpdates" in out


def test_tracebacks_are_redacted_too():
    logger, stream = _logger()
    try:
        raise RuntimeError(f"GET https://api.telegram.org/file/bot{TOKEN}/x failed")
    except RuntimeError:
        logger.exception("download failed")
    assert "AAFakeSecret" not in stream.getvalue()


def test_plain_lines_are_untouched():
    assert log_redact.redact("bot started, 5 owners") == "bot started, 5 owners"
