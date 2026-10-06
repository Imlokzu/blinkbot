"""Numeric Python calculations in a separate, resource-limited Monty worker."""

from __future__ import annotations

import ast
import asyncio
import uuid
from pathlib import Path
import sysconfig
from weakref import WeakKeyDictionary

from calculation_schema import SCHEMA

MAX_CODE = 16_000
MAX_OUTPUT = 8_000
MAX_MEMORY = 32 * 1024 * 1024
RUN_SECONDS = 3.0
_slots: WeakKeyDictionary = WeakKeyDictionary()


class _OutputLimit(Exception):
    pass


def _bounded_program(code: str) -> str:
    # Capture and format the trailing value INSIDE the resource-limited worker.
    # Returning a huge host object and then calling str() would escape limits.
    tree = ast.parse(code)
    prefix = "_calculation_" + uuid.uuid4().hex
    result, stringify = prefix + "_value", prefix + "_str"
    if tree.body and isinstance(tree.body[-1], ast.Expr):
        tree.body[-1] = ast.Assign(targets=[ast.Name(id=result, ctx=ast.Store())], value=tree.body[-1].value)
    tree = ast.fix_missing_locations(tree)
    return (f"{result} = None\n{stringify} = str\n" + ast.unparse(tree) +
            f"\n{result} = '' if {result} is None else {stringify}({result})\n" +
            f"{result}[:{MAX_OUTPUT + 1}]\n")


async def python_calculate(code: str) -> dict:
    if not isinstance(code, str) or not code.strip() or len(code) > MAX_CODE:
        return {"error": "invalid_calculation_code"}
    try:
        program = _bounded_program(code)
    except (SyntaxError, ValueError, RecursionError):
        return {"error": "calculation_failed", "detail": "Invalid Python syntax"}
    try:
        from pydantic_monty import AsyncMonty, MontyError
    except ImportError:
        return {"error": "calculation_unavailable"}
    # Resolve the installed runtime explicitly; neither model input nor PATH
    # may select an executable. No mounts, OS callbacks or host functions.
    binary = Path(sysconfig.get_path("scripts")) / "monty"
    if not binary.is_file():
        return {"error": "calculation_unavailable"}
    chunks: list[str] = []
    length = 0
    output_exceeded = False

    def printed(_stream: str, text: str) -> None:
        nonlocal length, output_exceeded
        if length + len(text) > MAX_OUTPUT:
            output_exceeded = True
            raise _OutputLimit
        chunks.append(text)
        length += len(text)

    try:
        # The context owns this worker through completion AND cancellation.
        # Fresh sessions never carry one user's variables into another call.
        loop = asyncio.get_running_loop()
        slots = _slots.setdefault(loop, asyncio.Semaphore(2))
        async with asyncio.timeout(RUN_SECONDS + 3), slots:
            async with AsyncMonty(binary_path=str(binary), max_processes=1, request_timeout=RUN_SECONDS + 1) as pool:
                async with pool.checkout(
                    limits={"max_memory": MAX_MEMORY, "max_feed_duration_secs": RUN_SECONDS},
                    print_flush_interval=0.01,
                ) as session:
                    result = await session.feed_run(program, print_callback=printed)
        if not isinstance(result, str):
            return {"error": "calculation_failed"}
        value = result
        if length + len(value) > MAX_OUTPUT:
            return {"error": "calculation_output_limit"}
        return {"ok": True, "stdout": "".join(chunks), "result": value}
    except asyncio.CancelledError:
        raise
    except _OutputLimit:
        return {"error": "calculation_output_limit"}
    except TimeoutError:
        return {"error": "calculation_timeout"}
    except MontyError as exc:
        if output_exceeded:
            return {"error": "calculation_output_limit"}
        if hasattr(exc, "exception"):
            failure = exc.exception()
            if isinstance(failure, TimeoutError):
                return {"error": "calculation_timeout"}
            if isinstance(failure, MemoryError):
                return {"error": "calculation_memory_limit"}
        # Only the sandbox's bounded diagnostic is useful to the model.
        return {"error": "calculation_failed", "detail": str(exc)[:1000]}


SCHEMAS = [{"type": "function", "function": {
    "name": SCHEMA["name"], "description": SCHEMA["description"], "parameters": SCHEMA["inputSchema"],
}}]
HANDLERS = {"python_calculate": python_calculate}
