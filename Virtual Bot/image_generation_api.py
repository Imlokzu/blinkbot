"""Operator-only readiness checks for the host's ChatGPT image provider."""
from fastapi import APIRouter, Depends, Response
import image_generation


def router(require_operator):
    routes = APIRouter(prefix='/api/images', dependencies=[Depends(require_operator)])

    @routes.get('/status')
    async def status(response: Response):
        response.headers['Cache-Control'] = 'no-store'
        return await image_generation.status()

    return routes
