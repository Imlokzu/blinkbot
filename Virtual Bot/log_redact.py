"""
Keep bot tokens out of the logs.

Telegram puts the token in the URL path (`/bot<id>:<secret>/getUpdates`),
and httpx logs every request URL at INFO — so the full token landed in
every log line of the long-poll loop. This filter rewrites any such token
to `/bot<id>:***` before a record is written. The bot id stays: it tells
which bot a line is about and is not a secret.

The filter sits on the root handlers, so it covers every logger (httpx,
httpcore, our own warnings that quote a failing URL).
"""

from __future__ import annotations

import logging
import re

# id, then the secret: Telegram secrets are 30+ of [A-Za-z0-9_-]
_TOKEN_RE = re.compile(r"(bot\d{5,}):[A-Za-z0-9_-]{20,}")


def redact(text: str) -> str:
    return _TOKEN_RE.sub(r"\1:***", text)


class RedactTokens(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        message = record.getMessage()
        clean = redact(message)
        if clean != message:
            # Freeze the cleaned text: args would re-insert the token when a
            # handler formats the record again.
            record.msg = clean
            record.args = ()
        if record.exc_info and not record.exc_text:
            # httpx errors quote the URL; a formatter uses exc_text when it
            # is already set, so the traceback is cleaned here once.
            record.exc_text = redact(logging.Formatter().formatException(record.exc_info))
        return True


def install() -> None:
    """Attach the filter to every root handler (call after basicConfig)."""
    flt = RedactTokens()
    for handler in logging.getLogger().handlers:
        if not any(isinstance(f, RedactTokens) for f in handler.filters):
            handler.addFilter(flt)
