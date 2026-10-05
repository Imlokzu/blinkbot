"""Durable mobile adapter; the parent injects identity and the existing turn.

``run_turn(job)`` returns an async iterator of ``(event_name, JSON_dict)``.
``job`` is the persisted request plus ``id`` and original ``user_id``. The
callback must apply model/effort per turn, never change global model settings,
and must clean up the underlying chat iterator when it is cancelled/closed.
"""
from __future__ import annotations

import asyncio
import codecs
from collections.abc import AsyncIterator, Awaitable, Callable
from contextlib import asynccontextmanager
from contextvars import ContextVar, copy_context
from copy import deepcopy
from datetime import datetime
import inspect
import json
import math
import os
from pathlib import Path
import tempfile
from typing import Any, Literal
from urllib.parse import urlencode, urlsplit

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from fastapi.responses import FileResponse, JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, ValidationError, field_validator, model_validator

from mobile_store import MobileStore, StoreError, TERMINAL, TOKEN_PREFIX, _json

RunTurn = Callable[[dict], AsyncIterator[tuple[str, dict]]]
_default_store: MobileStore | None = None
_turn_options: ContextVar[dict | None] = ContextVar("mobile_turn_options", default=None)
Effort = Literal["none", "off", "minimal", "low", "medium", "high", "xhigh", "adaptive", "max", "ultra"]


def current_turn_options() -> dict | None:
    """Parent routing hook, available throughout iteration and child chat tasks.

    Return a copy so a title task or provider cannot change another task's
    selected model/effort. The scheduler/PC request context stays untouched.
    """
    options = _turn_options.get()
    return dict(options) if options is not None else None


def create_background_task(coroutine):
    """Title/housekeeping tasks keep owner context but not mobile turn options."""
    context = copy_context()
    context.run(_turn_options.set, None)
    return context.run(asyncio.create_task, coroutine)


async def iter_chat_events(response: Any) -> AsyncIterator[tuple[str, dict]]:
    """Parse the existing chat_turn StreamingResponse and own its cleanup.

    Handles complete local frames as well as fragmented UTF-8/CRLF chunks.
    Original event payloads are unchanged; upstream IDs are intentionally not
    reused because this adapter provides its own durable per-job sequence.
    """
    iterator = response.body_iterator
    decoder = codecs.getincrementaldecoder("utf-8")()
    buffer, event = "", "message"
    data: list[str] = []
    try:
        async for chunk in iterator:
            buffer += decoder.decode(chunk) if isinstance(chunk, bytes) else chunk
            while "\n" in buffer:
                line, buffer = buffer.split("\n", 1)
                line = line.removesuffix("\r")
                if not line:
                    if data:
                        try:
                            payload = json.loads("\n".join(data))
                        except ValueError:
                            raise StoreError("invalid_chat_event", 502) from None
                        if not isinstance(payload, dict):
                            raise StoreError("invalid_chat_event", 502)
                        yield event, payload
                    event, data = "message", []
                elif not line.startswith(":"):
                    field, _, value = line.partition(":")
                    value = value.removeprefix(" ")
                    if field == "event":
                        event = value
                    elif field == "data":
                        data.append(value)
        # A partially delivered SSE frame is not an accepted event. The
        # runtime diagnoses an exhausted stream without done as incomplete.
    finally:
        if hasattr(iterator, "aclose"):
            await iterator.aclose()


def get_store() -> MobileStore:
    """Lazy initialization avoids touching runtime data during import/tests."""
    global _default_store
    if _default_store is None:
        path = os.environ.get("MOBILE_DATABASE_PATH")
        _default_store = MobileStore(Path(path).expanduser() if path else Path(__file__).resolve().parent / "runtime" / "mobile.sqlite3")
    return _default_store


def authenticate_token(token: str, *, store: MobileStore | None = None) -> str | None:
    """Parent hook: call BEFORE Clerk/disabled-auth; check ``is not None``.

    Nonmobile tokens return None. Invalid cbm_ tokens raise HTTP 401, including
    when the parent allows unauthenticated local-owner requests.
    """
    if not token.startswith(TOKEN_PREFIX):
        return None
    try:
        return (store or get_store()).authenticate_token(token)
    except StoreError as exc:
        raise HTTPException(exc.status, {"code": exc.code}) from None


def _token(request: Request) -> str:
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()
        if token:
            return token
    return (request.headers.get("x-clerk-token") or request.query_params.get("token") or "").strip()


def _origin(server: str) -> str:
    parsed = urlsplit(server)
    try:
        port = parsed.port
    except ValueError:
        raise ValueError("invalid_server_origin") from None
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username is not None
            or parsed.password is not None or parsed.path not in {"", "/"}
            or parsed.query or parsed.fragment or any(ch.isspace() for ch in server)
            or port == 0):
        raise ValueError("invalid_server_origin")
    return server.rstrip("/")


def _mobile_update(platform: str, version_code: int) -> dict[str, Any]:
    """Return operator-published update metadata without embedding binaries."""
    prefix = "MOBILE_UPDATE_ANDROID" if platform == "android" else "MOBILE_UPDATE_IOS"
    try:
        published_code = int(os.environ.get(f"{prefix}_VERSION_CODE", "0"))
    except ValueError:
        published_code = 0
    version_name = os.environ.get(f"{prefix}_VERSION", "").strip()
    changelog = [line.strip(" -*\t") for line in os.environ.get(f"{prefix}_CHANGELOG", "").splitlines() if line.strip()]
    update_url = os.environ.get(f"{prefix}_URL", "").strip() or None
    if platform == "android" and os.environ.get("MOBILE_UPDATE_ANDROID_FILE", "").strip() and not update_url:
        origin = os.environ.get("MOBILE_API_ORIGIN", "https://api-bot.waveio.me").rstrip("/")
        update_url = f"{origin}/api/mobile/update/download"
    return {
        "available": published_code > max(0, version_code) and bool(version_name),
        "version_name": version_name,
        "version_code": published_code,
        "changelog": changelog[:32],
        "url": update_url,
        "ios_url": os.environ.get("MOBILE_UPDATE_IOS_URL", "").strip() or None,
        "sha256": os.environ.get(f"{prefix}_SHA256", "").strip() or None,
        "mandatory": os.environ.get(f"{prefix}_MANDATORY", "").lower() in {"1", "true", "yes"},
    }


class PairingRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    server: str = Field(default="", max_length=512)


class ExchangeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    code: str = Field(min_length=1, max_length=128)
    device_name: str = Field(min_length=1, max_length=80)
    platform: Literal["android", "ios"]

    @field_validator("device_name")
    @classmethod
    def name(cls, value: str) -> str:
        value = value.strip()
        if not value or any(ord(ch) < 32 for ch in value):
            raise ValueError("invalid_device_name")
        return value


class MessageRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    client_id: str = Field(min_length=1, max_length=128)
    session_id: str = Field(default="", max_length=64, pattern=r"^(?:[A-Za-z0-9_-]{1,64})?$")
    message: str = Field(max_length=32_000)
    attachments: list[dict[str, Any]] = Field(default_factory=list, max_length=8)
    model: str = Field(default="", max_length=200)
    # The parent must bridge these gateway levels, not blindly put them into
    # ChatRequest (its legacy reasoning_effort field accepts only four values).
    reasoning_effort: Effort = "none"
    delivery: Literal["queue", "steer"] = "queue"
    scheduled_at: float | None = None

    @model_validator(mode="after")
    def content(self):
        if not self.message.strip() and not self.attachments:
            raise ValueError("message_or_attachment_required")
        return self

    @field_validator("scheduled_at", mode="before")
    @classmethod
    def schedule(cls, value: Any) -> float | None:
        if value is None:
            return None
        if isinstance(value, str):
            try:
                timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
                if timestamp.tzinfo is None:
                    raise ValueError("schedule_timezone_required")
                value = timestamp.timestamp()
            except (ValueError, OverflowError):
                raise ValueError("invalid_schedule") from None
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise ValueError("invalid_schedule")
        return float(value)

    @field_validator("client_id")
    @classmethod
    def client(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("invalid_client_id")
        return value

    @field_validator("attachments")
    @classmethod
    def bounded_attachments(cls, value: list[dict]) -> list[dict]:
        if len(_json(value).encode("utf-8")) > 128_000:
            raise ValueError("attachments_too_large")
        return value


class ForkRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    client_id: str = Field(min_length=1, max_length=128)
    message_id: str = Field(min_length=1, max_length=128)
    action: Literal["edit", "regenerate"]
    message: str | None = Field(default=None, max_length=32_000)
    attachments: list[dict[str, Any]] | None = Field(default=None, max_length=8)
    model: str = Field(default="", max_length=200)
    reasoning_effort: Effort = "none"

    @field_validator("client_id")
    @classmethod
    def client(cls, value: str) -> str:
        return MessageRequest.client(value)

    @field_validator("attachments")
    @classmethod
    def bounded_attachments(cls, value: list[dict] | None) -> list[dict] | None:
        return MessageRequest.bounded_attachments(value) if value is not None else None


def _prepare_fork(user_id: str, source_id: str, req: ForkRequest) -> dict:
    """Capture an immutable prefix in the existing owner namespace, read-only."""
    import brain_context
    import chat_store

    if not chat_store.is_valid_id(source_id):
        raise StoreError("invalid_session_id")
    if req.action == "edit" and req.message is None:
        raise StoreError("edited_message_required", 422)
    if req.action == "regenerate" and (req.message is not None or req.attachments is not None):
        raise StoreError("regeneration_content_override", 422)
    with brain_context.set_clerk_user(user_id), chat_store.set_kind(chat_store.KIND_CHAT):
        source = chat_store.load(source_id)
    messages = source.get("messages", [])
    target = next((i for i, item in enumerate(messages) if item.get("id") == req.message_id), None)
    if target is None:
        raise StoreError("message_not_found", 404)
    wanted_role = "user" if req.action == "edit" else "assistant"
    if messages[target].get("role") != wanted_role:
        raise StoreError("invalid_fork_target", 422)
    user_at = target if req.action == "edit" else next(
        (i for i in range(target - 1, -1, -1) if messages[i].get("role") == "user"), None)
    if user_at is None:
        raise StoreError("regeneration_user_missing", 422)
    original = messages[user_at]
    message = req.message if req.action == "edit" else original.get("content", "")
    attachments = req.attachments if req.attachments is not None else original.get("attachments", [])
    # Validate inherited content through the ordinary submission schema too.
    try:
        submission = MessageRequest(client_id=req.client_id, message=message, attachments=attachments,
                                    model=req.model, reasoning_effort=req.reasoning_effort).model_dump()
    except ValidationError:
        raise StoreError("invalid_saved_message", 422) from None
    submission["fork"] = {
        "source_session_id": source_id, "message_id": req.message_id, "action": req.action,
        "title": source.get("title", ""), "project": source.get("project", ""),
        "messages": deepcopy(messages[:user_at]),
        "participants": deepcopy(source.get("participants", [])),
        "participant_name": original.get("participant", ""),
    }
    return submission


def materialize_fork(job: dict, *, created_at: float) -> list[dict[str, str]]:
    """Publish a new shared history file atomically, never replacing the source.

    Private chat_store path resolution is confined to this compatibility seam;
    the parent can later replace it with a public chat_store fork primitive.
    """
    import brain_context
    import chat_store

    fork = job["fork"]
    if job["session_id"] == fork["source_session_id"]:
        raise StoreError("invalid_fork_destination", 409)
    with brain_context.set_clerk_user(job["user_id"]), chat_store.set_kind(chat_store.KIND_CHAT):
        path = chat_store._path(job["session_id"])
        snapshot = {"id": job["session_id"], "title": fork["title"], "project": fork["project"],
                    "created": int(created_at), "updated": int(created_at), "messages": fork["messages"],
                    "participants": fork["participants"], "events": [],
                    "fork_of": {key: fork[key] for key in ("source_session_id", "message_id", "action")},
                    "mobile_fork_job_id": job["id"]}
        # Hard-link publication is atomic and refuses an existing destination.
        # That also prevents a concurrent PC write being silently overwritten.
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".mobile-fork-", delete=False) as tmp:
            temporary = Path(tmp.name)
            try:
                tmp.write(_json(snapshot).encode("utf-8"))
                tmp.flush()
                os.fsync(tmp.fileno())
                try:
                    os.link(temporary, path)
                except FileExistsError:
                    if chat_store.load(job["session_id"]).get("mobile_fork_job_id") != job["id"]:
                        raise StoreError("fork_destination_exists", 409) from None
            finally:
                temporary.unlink(missing_ok=True)
        return chat_store.history(job["session_id"], chat_store.MAX_MESSAGES)


def _summary(job: dict) -> dict:
    payload = job["payload"]
    fork = payload.get("fork")
    return {"id": job["id"], "session_id": job["session_id"], "state": job["state"],
            "message": payload["message"], "scheduled_at": job["scheduled_at"], "model": payload["model"],
            **({"fork": {key: fork[key] for key in ("source_session_id", "message_id", "action")}} if fork else {}),
            **({"error": job["error"]} if job.get("error") else {})}


class MobileRuntime:
    """Host-owned tasks survive SSE disconnects; only Stop/shutdown cancels them."""

    def __init__(self, store: MobileStore, run_turn: RunTurn, *, scan_interval: float = 0.25, concurrency: int = 4):
        if scan_interval <= 0 or concurrency < 1:
            raise ValueError("invalid_runtime_settings")
        self.store, self.run_turn = store, run_turn
        self.scan_interval, self.concurrency = scan_interval, concurrency
        self.tasks: dict[str, asyncio.Task] = {}
        self.wake = asyncio.Event()
        self.scheduler: asyncio.Task | None = None
        self.lock = None
        self.closing = False

    def notify(self) -> None:
        """Wake this scheduler after another host module commits a submission."""
        self.wake.set()

    def submit(self, user_id: str, payload: dict, *, request: dict | None = None) -> dict:
        """Share idempotency and the same queue with sibling API modules.

        Payload must already be validated by the caller. An optional original
        request fingerprints immutable operations whose prepared data differs.
        The returned record is the store's full job, not a transport summary.
        """
        job = self.store.submit_request(user_id, payload, request=request)
        self.notify()
        return job

    async def start(self) -> None:
        if self.scheduler is not None:
            return
        self.lock = self.store.runner_lock()
        try:
            self.store.recover()
            self.closing = False
            self.scheduler = asyncio.create_task(self._scan())
        except BaseException:
            self.lock.close()
            self.lock = None
            raise

    async def close(self) -> None:
        self.closing = True
        if self.scheduler is not None:
            self.scheduler.cancel()
            await asyncio.gather(self.scheduler, return_exceptions=True)
            self.scheduler = None
        tasks = list(self.tasks.values())
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if self.lock is not None:
            self.lock.close()
            self.lock = None

    async def _scan(self) -> None:
        while True:
            self.wake.clear()
            while len(self.tasks) < self.concurrency:
                job = self.store.claim_next()
                if job is None:
                    break
                task = asyncio.create_task(self._execute(job))
                self.tasks[job["id"]] = task
                # A task cancelled before its first instruction still needs a
                # durable terminal state and removal from the concurrency set.
                task.add_done_callback(lambda _task, claimed=job: self._settled(claimed))
            try:
                await asyncio.wait_for(self.wake.wait(), self.scan_interval)
            except asyncio.TimeoutError:
                pass

    def _settled(self, job: dict) -> None:
        self.tasks.pop(job["id"], None)
        current = self.store.get(job["user_id"], job["id"])
        if current["state"] not in TERMINAL:
            self.store.finish(job["id"], "interrupted" if self.closing else "stopped", pause=True)
        self.wake.set()

    async def _execute(self, job: dict) -> None:
        iterator = None
        finished = False
        failed = False
        failure_code = "turn_failed"
        state, error, pause = "failed", "incomplete_stream", True
        run_job = {**job["payload"], "id": job["id"], "user_id": job["user_id"]}
        context_token = _turn_options.set({"model": run_job["model"], "reasoning_effort": run_job["reasoning_effort"],
                                           "user_id": job["user_id"], "session_id": job["session_id"],
                                           "seed_history": "fork" in run_job})
        try:
            if "fork" in run_job:
                run_job["history"] = materialize_fork(run_job, created_at=job["created_at"])
                run_job["participant_name"] = run_job["fork"]["participant_name"]
            iterator = self.run_turn(run_job)
            # Also allow an async factory which returns the async iterator.
            if inspect.isawaitable(iterator):
                iterator = await iterator
            async for event, data in iterator:
                if not self.store.record(job["id"], event, data):
                    break
                finished |= event == "done"
                failed |= event == "error"
                if event == "error" and data.get("error") == "mobile_image_model_unavailable":
                    failure_code = "mobile_image_model_unavailable"
            if failed or not finished:
                error = failure_code if failed else "incomplete_stream"
            else:
                state, error, pause = "completed", None, False
        except asyncio.CancelledError:
            state, error, pause = "interrupted" if self.closing else "stopped", None, True
            raise
        except Exception:
            # Runtime/provider exception messages can contain secrets. The
            # parent owns diagnostics; the mobile transport emits only a code.
            state, error, pause = "failed", "turn_failed", True
        finally:
            try:
                if iterator is not None and hasattr(iterator, "aclose"):
                    await iterator.aclose()
            except asyncio.CancelledError:
                state, error, pause = "interrupted" if self.closing else "stopped", None, True
                raise
            except Exception:
                state, error, pause = "failed", "turn_cleanup_failed", True
            finally:
                # Queue advancement waits for callback cleanup too: aclose may
                # still hold a gateway subscription or a shared turn gate.
                try:
                    self.store.finish(job["id"], state, error=error, pause=pause)
                finally:
                    _turn_options.reset(context_token)

    async def stop(self, user_id: str, job_id: str) -> dict:
        job = self.store.stop(user_id, job_id)
        task = self.tasks.get(job_id)
        if task is not None and job["state"] == "stopping":
            task.cancel()
            # If a provider ignores cancellation, report stopping rather than
            # falsely claiming it stopped or launching the next queued turn.
            await asyncio.wait({task}, timeout=5)
        return self.store.get(user_id, job_id)


class MobileRouter(APIRouter):
    """Public resources for sibling routers; only this router owns lifespan."""

    store: MobileStore
    runtime: MobileRuntime
    identity: Callable[[Request], Awaitable[str]]


def router(require_user, require_operator, run_turn: RunTurn, *, store: MobileStore | None = None,
           server_origin: str = "", scan_interval: float = 0.25, concurrency: int = 4) -> MobileRouter:
    selected_store = store or get_store()
    configured_origin = _origin(server_origin) if server_origin else ""
    runtime = MobileRuntime(selected_store, run_turn, scan_interval=scan_interval, concurrency=concurrency)

    @asynccontextmanager
    async def lifespan(_app):
        await runtime.start()
        try:
            yield
        finally:
            await runtime.close()

    async def private(response: Response):
        response.headers["Cache-Control"] = "no-store"

    routes = MobileRouter(prefix="/api/mobile", lifespan=lifespan, dependencies=[Depends(private)])
    routes.store = selected_store
    routes.runtime = runtime

    async def identity(request: Request) -> str:
        mobile = authenticate_token(_token(request), store=selected_store)
        return mobile if mobile is not None else await require_user(request)

    routes.identity = identity

    def call(operation):
        try:
            return operation()
        except StoreError as exc:
            headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after is not None else None
            raise HTTPException(exc.status, {"code": exc.code}, headers=headers) from None

    @routes.post("/pairings", dependencies=[Depends(require_operator)])
    async def pairing(req: PairingRequest, user_id: str = Depends(identity)):
        try:
            origin = _origin(req.server or configured_origin)
        except ValueError:
            raise HTTPException(422, {"code": "invalid_server_origin"}) from None
        if configured_origin and origin != configured_origin:
            raise HTTPException(422, {"code": "server_origin_mismatch"})
        result = call(lambda: selected_store.create_pairing(user_id))
        from mobile_pair_qr import svg
        payload = "claudebot://pair?" + urlencode({"server": origin, "code": result["code"]})
        return {**result, "qr_payload": payload, "qr_svg": svg(payload)}

    @routes.post("/pair/exchange")
    async def exchange(req: ExchangeRequest):
        return call(lambda: selected_store.exchange(req.code, req.device_name, req.platform))

    @routes.get("/devices")
    async def devices(user_id: str = Depends(identity)):
        return {"devices": selected_store.devices(user_id)}

    @routes.delete("/devices/{device_id}")
    async def revoke(device_id: str, user_id: str = Depends(identity)):
        call(lambda: selected_store.revoke(user_id, device_id))
        return {"ok": True}

    @routes.get("/update/download")
    async def download_update(user_id: str = Depends(identity)):
        if os.environ.get("MOBILE_UPDATE_ANDROID_FILE", "").strip() == "":
            raise HTTPException(404, {"code": "update_unavailable"})
        path = Path(os.environ["MOBILE_UPDATE_ANDROID_FILE"]).expanduser()
        try:
            path = path.resolve(strict=True)
            if path.suffix.lower() != ".apk" or not path.is_file() or path.stat().st_size > 100 * 1024 * 1024:
                raise ValueError
        except (OSError, ValueError):
            raise HTTPException(404, {"code": "update_unavailable"}) from None
        return FileResponse(path, media_type="application/vnd.android.package-archive", filename=path.name,
                            headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})

    @routes.get("/capabilities")
    async def capabilities(
        platform: Literal["android", "ios"] = Query(default="android"),
        version_code: int = Query(default=0, ge=0, le=10_000_000),
        user_id: str = Depends(identity),
    ):
        return {"version": 1, "pairing": True, "queue": True, "steer": False,
                "stop": True, "scheduled_send": True, "event_replay": True,
                "idempotency": True, "history_fork": True, "push": False,
                "update": _mobile_update(platform, version_code)}

    @routes.post("/messages")
    async def submit(req: MessageRequest, user_id: str = Depends(identity)):
        job = call(lambda: runtime.submit(user_id, req.model_dump()))
        if job["state"] == "unsupported":
            return JSONResponse(_summary(job), status_code=501, headers={"Cache-Control": "no-store"})
        return _summary(job)

    @routes.post("/sessions/{source_id}/fork")
    async def fork(source_id: str, req: ForkRequest, user_id: str = Depends(identity)):
        original_request = {"operation": "fork", "source_session_id": source_id, **req.model_dump()}
        previous = call(lambda: selected_store.find_request(user_id, req.client_id, original_request))
        if previous is not None:
            return _summary(previous)
        submission = call(lambda: _prepare_fork(user_id, source_id, req))
        job = call(lambda: runtime.submit(user_id, submission, request=original_request))
        return _summary(job)

    @routes.get("/messages")
    async def messages(session_id: str = Query(default="", max_length=64), user_id: str = Depends(identity)):
        jobs = selected_store.messages(user_id, session_id)
        return {"messages": [{**_summary(job), "client_id": job["client_id"],
                "message": job["payload"]["message"], "attachments": job["payload"]["attachments"],
                "model": job["payload"]["model"], "reasoning_effort": job["payload"]["reasoning_effort"],
                "delivery": job["delivery"], "scheduled_at": job["scheduled_at"],
                "created_at": job["created_at"], "updated_at": job["updated_at"],
                "conversation_paused": bool(job["conversation_paused"])} for job in jobs]}

    @routes.get("/messages/{job_id}/events")
    async def events(job_id: str, request: Request, after: int = Query(default=0, ge=0), user_id: str = Depends(identity)):
        call(lambda: selected_store.get(user_id, job_id))
        if "after" not in request.query_params and request.headers.get("last-event-id"):
            try:
                after = int(request.headers["last-event-id"])
                if after < 0:
                    raise ValueError
            except ValueError:
                raise HTTPException(422, {"code": "invalid_event_cursor"}) from None

        async def stream():
            cursor = after
            while True:
                batch = selected_store.events(user_id, job_id, cursor)
                for entry in batch:
                    cursor = entry["seq"]
                    data = entry["data"]
                    if entry["event"] == "mobile_state" and data.get("state") == "failed":
                        error = selected_store.get(user_id, job_id).get("error")
                        if error == "mobile_image_model_unavailable":
                            data = {**data, "error": error}
                    yield f"id: {cursor}\nevent: {entry['event']}\ndata: {_json(data)}\n\n"
                if batch:
                    continue  # Drain all committed events before closing.
                if selected_store.get(user_id, job_id)["state"] in TERMINAL:
                    break
                if await request.is_disconnected():
                    break  # This listener never owns the runner task.
                yield ": keepalive\n\n"
                await asyncio.sleep(runtime.scan_interval)

        # Cloudflare may transform/compress small frames unless the origin opts
        # out. Each yield must reach the listener while the provider is running.
        return StreamingResponse(stream(), media_type="text/event-stream", headers={
            "Cache-Control": "no-store, no-transform", "X-Accel-Buffering": "no",
        })

    @routes.post("/messages/{job_id}/stop")
    async def stop(job_id: str, user_id: str = Depends(identity)):
        try:
            return _summary(await runtime.stop(user_id, job_id))
        except StoreError as exc:
            raise HTTPException(exc.status, {"code": exc.code}) from None

    @routes.post("/sessions/{session_id}/resume")
    async def resume(session_id: str, user_id: str = Depends(identity)):
        call(lambda: selected_store.resume(user_id, session_id))
        runtime.notify()
        return {"ok": True, "session_id": session_id, "paused": False}

    return routes
