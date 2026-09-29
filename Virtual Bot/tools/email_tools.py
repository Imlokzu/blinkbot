"""
Agent Email Tools for Virtual Bot.
Provides mailbox management, email reading, and automated OTP/verification code extraction
via Cloudflare Email Routing Worker at mail.waveio.me.
"""

from __future__ import annotations

import asyncio
import logging
import os
import re
from typing import Any

from datetime import datetime, timedelta, timezone

import httpx

log = logging.getLogger("virtual_bot.tools.email")

DEFAULT_API_URL = "https://mail.waveio.me"
DEFAULT_DOMAIN = "ag.waveio.me"
OTP_FRESHNESS_SECONDS = 180


def _get_config() -> tuple[str, str, str]:
    api_url = os.getenv("AGENT_MAIL_API_URL", DEFAULT_API_URL).rstrip("/")
    # No default: a key baked into the source is published with the repo.
    # It lives in Virtual Bot/.env (git-ignored) as AGENT_MAIL_API_KEY.
    api_key = os.getenv("AGENT_MAIL_API_KEY", "").strip()
    domain = os.getenv("AGENT_MAIL_DOMAIN", DEFAULT_DOMAIN).strip()
    return api_url, api_key, domain


def _normalize_address(agent_name_or_email: str, domain: str) -> str:
    cleaned = (agent_name_or_email or "lokzu").strip().lower()
    if "@" in cleaned:
        return cleaned
    cleaned = re.sub(r"[^a-z0-9._-]", "", cleaned)
    return f"{cleaned}@{domain}"


def _is_fresh(otp_data: dict[str, Any], not_before: datetime) -> bool:
    raw = otp_data.get("received_at") or ""
    try:
        received = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return True  # no usable timestamp: do not hide the code
    return received >= not_before


async def get_agent_email(agent_name: str = "lokzu") -> dict[str, Any]:
    """
    Get the designated email address for the agent to use during signups or correspondence.
    """
    _, _, domain = _get_config()
    address = _normalize_address(agent_name, domain)
    return {
        "status": "ok",
        "agent_name": agent_name,
        "email": address,
        "domain": domain,
        "description": f"Use this email address for service registrations and receiving OTP codes.",
    }


async def check_agent_inbox(
    agent_name: str = "lokzu",
    unread_only: bool = False,
    limit: int = 10,
) -> dict[str, Any]:
    """
    List recent emails in the agent mailbox, newest first. Each entry is a
    summary (sender, subject, preview, attachments, code/link); the whole
    message is read with read_agent_email.
    """
    api_url, api_key, domain = _get_config()
    target_email = _normalize_address(agent_name, domain)

    headers = {"X-API-Key": api_key}
    params: dict[str, Any] = {"to": target_email, "limit": max(1, min(int(limit or 10), 50))}
    if unread_only:
        params["unread"] = "1"
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(f"{api_url}/api/inbox", params=params, headers=headers)
            if resp.status_code != 200:
                return {
                    "status": "error",
                    "code": resp.status_code,
                    "error": f"Mail worker returned {resp.status_code}: {resp.text}",
                }
            data = resp.json()
            messages = [
                {
                    "id": m.get("id"),
                    "unread": not m.get("seen", False),
                    "from": m.get("from"),
                    "subject": m.get("subject"),
                    "date": m.get("date") or m.get("received_at"),
                    "preview": m.get("snippet"),
                    "otp_code": m.get("otp_code"),
                    "verification_link": m.get("verification_link"),
                    "attachments": [a.get("filename") for a in m.get("attachments") or []],
                }
                for m in data.get("messages", [])
            ]
            return {
                "status": "ok",
                "email": target_email,
                "total": data.get("total", len(messages)),
                "unread": data.get("unread"),
                "count": len(messages),
                "messages": messages,
            }
    except Exception as exc:
        log.warning("Failed to check agent inbox: %s", exc)
        return {
            "status": "error",
            "email": target_email,
            "error": str(exc),
        }


async def read_agent_email(
    message_id: str,
    agent_name: str = "lokzu",
    max_chars: int = 8000,
) -> dict[str, Any]:
    """
    Open one email in full: headers, the complete text, links and the
    attachment list. Marks it as read.
    """
    api_url, api_key, domain = _get_config()
    target_email = _normalize_address(agent_name, domain)
    if not message_id:
        return {"status": "error", "error": "message_id is required (take it from check_agent_inbox)"}

    headers = {"X-API-Key": api_key}
    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.get(
                f"{api_url}/api/message",
                params={"to": target_email, "id": message_id},
                headers=headers,
            )
            if resp.status_code != 200:
                return {
                    "status": "error",
                    "code": resp.status_code,
                    "error": f"Mail worker returned {resp.status_code}: {resp.text}",
                }
            m = resp.json()
    except Exception as exc:
        log.warning("Failed to read agent email: %s", exc)
        return {"status": "error", "email": target_email, "error": str(exc)}

    # Records stored before the worker kept whole messages only have `body`.
    text = (m.get("text") or m.get("body") or "").strip()
    limit = max(500, int(max_chars or 8000))
    truncated = len(text) > limit
    recipients = m.get("recipients") or {}
    return {
        "status": "ok",
        "id": m.get("id"),
        "from": m.get("from"),
        "to": [a.get("address") for a in recipients.get("to") or []],
        "cc": [a.get("address") for a in recipients.get("cc") or []],
        "reply_to": [a.get("address") for a in recipients.get("reply_to") or []],
        "date": m.get("date") or m.get("received_at"),
        "subject": m.get("subject"),
        "text": text[:limit],
        "truncated": truncated,
        "links": m.get("links") or [],
        "attachments": [
            {"filename": a.get("filename"), "mime_type": a.get("mime_type"), "size": a.get("size")}
            for a in m.get("attachments") or []
        ],
        "otp_code": m.get("otp_code"),
        "verification_link": m.get("verification_link"),
    }


async def wait_for_otp_code(
    agent_name: str = "lokzu",
    service: str = "",
    max_wait_seconds: int = 40,
) -> dict[str, Any]:
    """
    Wait and poll for an incoming OTP/verification code or link for this mailbox.
    """
    api_url, api_key, domain = _get_config()
    target_email = _normalize_address(agent_name, domain)

    headers = {"X-API-Key": api_key}
    poll_interval = 3
    deadline = asyncio.get_event_loop().time() + max(5, min(max_wait_seconds, 120))
    # The mail often lands between submitting a form and calling this tool,
    # so a little before "now" still counts. Older entries belong to some
    # earlier signup and would hand back a code that no longer works.
    not_before = datetime.now(timezone.utc) - timedelta(seconds=OTP_FRESHNESS_SECONDS)

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            while asyncio.get_event_loop().time() < deadline:
                resp = await client.get(
                    f"{api_url}/api/latest-otp",
                    params={"to": target_email},
                    headers=headers,
                )
                if resp.status_code == 200:
                    payload = resp.json()
                    otp_data = payload.get("otp")
                    if otp_data and (otp_data.get("code") or otp_data.get("link")) and _is_fresh(otp_data, not_before):
                        # If specific service filter requested, check subject/from
                        if service:
                            needle = service.lower()
                            subj = (otp_data.get("subject") or "").lower()
                            sender = (otp_data.get("from") or "").lower()
                            if needle not in subj and needle not in sender:
                                await asyncio.sleep(poll_interval)
                                continue

                        return {
                            "status": "ok",
                            "found": True,
                            "email": target_email,
                            "otp_code": otp_data.get("code"),
                            "verification_link": otp_data.get("link"),
                            "from": otp_data.get("from"),
                            "subject": otp_data.get("subject"),
                            "received_at": otp_data.get("received_at"),
                        }

                await asyncio.sleep(poll_interval)

            return {
                "status": "timeout",
                "found": False,
                "email": target_email,
                "message": f"No verification code or link arrived within {max_wait_seconds} seconds.",
            }
    except Exception as exc:
        log.warning("Failed while waiting for OTP: %s", exc)
        return {
            "status": "error",
            "email": target_email,
            "error": str(exc),
        }


async def send_agent_email(
    to: str,
    subject: str,
    body: str,
    sender_name: str = "Claude Bot",
) -> dict[str, Any]:
    """
    Send an email to any recipient using the configured Brevo outbound API.
    """
    brevo_api_key = os.getenv("BREVO_API_KEY", "")
    if not brevo_api_key:
        return {"status": "error", "error": "BREVO_API_KEY is not configured in .env"}

    sender_email = os.getenv("BREVO_SENDER_EMAIL", "Lokzuhd@gmail.com")
    payload = {
        "sender": {"name": sender_name, "email": sender_email},
        "to": [{"email": to.strip()}],
        "subject": subject,
        "textContent": body,
    }

    headers = {
        "api-key": brevo_api_key,
        "Content-Type": "application/json",
    }

    try:
        async with httpx.AsyncClient(timeout=15.0) as client:
            resp = await client.post("https://api.brevo.com/v3/smtp/email", headers=headers, json=payload)
            if resp.status_code in (200, 201):
                data = resp.json()
                return {
                    "status": "ok",
                    "sent": True,
                    "to": to,
                    "message_id": data.get("messageId"),
                    "subject": subject,
                }
            return {
                "status": "error",
                "code": resp.status_code,
                "error": f"Brevo returned error {resp.status_code}: {resp.text}",
            }
    except Exception as exc:
        log.warning("Failed to send email via Brevo: %s", exc)
        return {"status": "error", "error": str(exc)}


SCHEMAS: list[dict] = [
    {
        "type": "function",
        "function": {
            "name": "get_agent_email",
            "description": "Отримати власну поштову адресу агента (@ag.waveio.me) для реєстрації на зовнішніх сервісах чи отримання пошти.",
            "parameters": {
                "type": "object",
                "properties": {
                    "agent_name": {
                        "type": "string",
                        "description": "Ім'я або псевдонім скриньки (типово 'lokzu').",
                    },
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "check_agent_inbox",
            "description": "List emails in the agent mailbox, newest first: id, unread, sender, subject, preview, attachments, code/link. Open one with read_agent_email.",
            "parameters": {
                "type": "object",
                "properties": {
                    "agent_name": {
                        "type": "string",
                        "description": "Mailbox name or address (default 'lokzu').",
                    },
                    "unread_only": {"type": "boolean", "description": "Only unread emails."},
                    "limit": {"type": "integer", "description": "How many (default 10)."},
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_agent_email",
            "description": "Read one email in full: sender, recipients, date, whole text, links, attachment names. Marks it read.",
            "parameters": {
                "type": "object",
                "properties": {
                    "message_id": {"type": "string", "description": "id from check_agent_inbox."},
                    "agent_name": {
                        "type": "string",
                        "description": "Mailbox name or address (default 'lokzu').",
                    },
                },
                "required": ["message_id"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "wait_for_otp_code",
            "description": "Очікувати на надходження коду підтвердження (OTP / verification code) або посилання активації від сервісу після реєстрації/входу.",
            "parameters": {
                "type": "object",
                "properties": {
                    "agent_name": {
                        "type": "string",
                        "description": "Ім'я або адреса скриньки (типово 'lokzu').",
                    },
                    "service": {
                        "type": "string",
                        "description": "Назва сервісу або ключове слово для фільтрації (напр. 'github', 'anthropic', 'opencode').",
                    },
                    "max_wait_seconds": {
                        "type": "integer",
                        "description": "Скільки секунд очікувати на лист (типово 40).",
                    },
                },
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "send_agent_email",
            "description": "Надіслати електронний лист від імені бота чи агента на будь-яку адресу (наприклад, власнику або зовнішньому контакту).",
            "parameters": {
                "type": "object",
                "properties": {
                    "to": {
                        "type": "string",
                        "description": "Електронна адреса одержувача (напр. 'lokzuhd@gmail.com').",
                    },
                    "subject": {
                        "type": "string",
                        "description": "Тема листа.",
                    },
                    "body": {
                        "type": "string",
                        "description": "Текст повідомлення.",
                    },
                },
                "required": ["to", "subject", "body"],
            },
        },
    },
]

HANDLERS = {
    "get_agent_email": get_agent_email,
    "check_agent_inbox": check_agent_inbox,
    "read_agent_email": read_agent_email,
    "wait_for_otp_code": wait_for_otp_code,
    "send_agent_email": send_agent_email,
}


