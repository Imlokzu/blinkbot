"""Operator-only source browsing and NotebookLM setup."""
from __future__ import annotations

from contextlib import asynccontextmanager
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import BaseModel, Field
import connectors


class ProfileRequest(BaseModel):
    profile: str = Field(default='', max_length=64)


class SourceRequest(BaseModel):
    notebook_id: str = Field(max_length=64)
    source_id: str = Field(max_length=64)


def router(require_operator, require_user) -> APIRouter:
    @asynccontextmanager
    async def lifetime(_app):
        try: yield
        finally: await connectors.stop()

    async def private(response: Response):
        response.headers['Cache-Control'] = 'no-store'

    routes = APIRouter(prefix='/api/connectors', dependencies=[Depends(require_operator), Depends(private)], lifespan=lifetime)

    def failure(exc):
        status = {'invalid_id': 400, 'invalid_profile': 400, 'invalid_request': 400,
            'file_too_large': 413, 'source_not_found': 404, 'source_not_ready': 409,
            'source_error': 422, 'config_conflict': 409, 'login_running': 409,
            'name_in_use': 409, 'timeout': 504}.get(exc.code, 503)
        return HTTPException(status_code=status, detail={'code': exc.code, 'message': exc.code},
            headers={'Cache-Control': 'no-store'})

    async def call(operation):
        try: return await operation
        except connectors.ConnectorError as exc:
            raise failure(exc) from None

    @routes.get('')
    async def inventory():
        try: return connectors.inventory()
        except connectors.ConnectorError as exc: raise failure(exc) from None

    @routes.get('/notebooklm/status')
    async def status(): return await call(connectors.status())

    @routes.post('/notebooklm/check')
    async def check(): return await call(connectors.status(check=True))

    @routes.post('/notebooklm/config')
    async def config(request: ProfileRequest): return await call(connectors.save_profile(request.profile))

    @routes.post('/notebooklm/login')
    async def login(): return await call(connectors.login())

    @routes.post('/notebooklm/enable')
    async def enable(): return await call(connectors.enable_agent())

    @routes.get('/notebooklm/notebooks')
    async def notebooks(): return await call(connectors.sdk('notebooks'))

    @routes.get('/notebooklm/notebooks/{notebook_id}/sources')
    async def sources(notebook_id: str):
        try: selected = connectors.valid_id(notebook_id)
        except connectors.ConnectorError as exc: raise HTTPException(status_code=400, detail=exc.code) from None
        return await call(connectors.sdk('sources', notebook=selected))

    @routes.post('/notebooklm/attach')
    async def attach(request: Request, source: SourceRequest):
        user_id = await require_user(request)
        return await call(connectors.attach(source.notebook_id, source.source_id, user_id))

    return routes
