"""Small typed SDK boundary executed inside the installed NotebookLM environment."""
from __future__ import annotations

import asyncio
import json
import sys
import re
import uuid


def failure(exc: Exception) -> str:
    from notebooklm import exceptions
    if isinstance(exc, (exceptions.AuthError, exceptions.AuthExtractionError,
            exceptions.HeadlessReauthError, FileNotFoundError)):
        return 'needs_login'
    if isinstance(exc, exceptions.NotFoundError):
        return 'source_not_found'
    if isinstance(exc, (TimeoutError, exceptions.RPCTimeoutError, exceptions.WaitTimeoutError)):
        return 'timeout'
    if isinstance(exc, exceptions.MissingDependencyError):
        return 'not_installed'
    return 'request_failed'


def source_status(source) -> str:
    if source.is_ready:
        return 'ready'
    return {'ERROR': 'error', 'PROCESSING': 'processing', 'PREPARING': 'processing'}.get(
        getattr(source.status, 'name', ''), 'unknown')


async def run(payload: dict) -> dict:
    from notebooklm import NotebookLMClient

    if not isinstance(payload, dict) or payload.get('operation') not in {'notebooks', 'sources', 'read'}:
        return {'error': 'invalid_request'}
    profile = payload.get('profile') or ''
    if not isinstance(profile, str) or profile and not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', profile):
        return {'error': 'invalid_request'}
    for key in (['notebook', 'source'] if payload['operation'] == 'read' else ['notebook'] if payload['operation'] == 'sources' else []):
        try:
            payload[key] = str(uuid.UUID(payload[key]))
        except (ValueError, TypeError, AttributeError, KeyError):
            return {'error': 'invalid_request'}
    async with NotebookLMClient.from_storage(profile=profile or None, timeout=25,
            rate_limit_max_retries=0, server_error_max_retries=0) as client:
        operation = payload['operation']
        if operation == 'notebooks':
            items = await client.notebooks.list()
            return {'notebooks': [{'id': item.id, 'title': (item.title or '')[:300], 'sources': item.sources_count} for item in items[:200]],
                'total': len(items), 'has_more': len(items) > 200}
        if operation == 'sources':
            items = await client.sources.list(payload['notebook'])
            return {'sources': [{'id': item.id, 'title': (item.title or '')[:300],
                'status': source_status(item)} for item in items[:200]],
                'total': len(items), 'has_more': len(items) > 200}
        if operation == 'read':
            source = await client.sources.get(payload['notebook'], payload['source'])
            if source_status(source) == 'error':
                return {'error': 'source_error'}
            if not source.is_ready:
                return {'error': 'source_not_ready'}
            full = await client.sources.get_fulltext(payload['notebook'], payload['source'])
            if len(full.content.encode('utf-8')) > 20 * 1024 * 1024:
                return {'error': 'file_too_large'}
            if full.source_id != payload['source']:
                return {'error': 'request_failed'}
            return {'title': full.title[:300], 'content': full.content, 'source': full.source_id}
        return {'error': 'invalid_request'}


if __name__ == '__main__':
    try:
        request = json.loads(sys.stdin.read(4096))
        result = asyncio.run(run(request))
    except Exception as exc:
        # SDK exceptions can contain Google response/cookie data. Only a stable
        # classification crosses the process boundary, never their message.
        try:
            code = failure(exc)
        except ImportError:
            code = 'not_installed'
        result = {'error': code}
    print(json.dumps(result, ensure_ascii=False))
