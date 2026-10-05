"""Exercise the installed gateway beyond the HTTP envelope with synthetic images.

OpenClaw 2026.9.5 defaults an omitted model.input to ["text"]. Its prompt image
loader then silently drops valid HTTP images. These source-backed diagnostics
distinguish that gateway metadata defect from mobile upload/model selection.
They do not change gateway configuration or contact a provider.
"""

import asyncio
import base64
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

import httpx
import pytest

import brains
import mobile_api
import mobile_routing
from test_mobile_transport_contract import JPEG
from test_mobile_web_image_parity import PNG, SOL, image_route


QWEN = "regolo/qwen3.5-122b"


def _module_export(root, pattern, name):
    for path in sorted(root.glob(pattern)):
        exports = path.read_text().rsplit("export {", 1)[-1]
        match = re.search(rf"\b{re.escape(name)} as (\w+)\b", exports)
        if match:
            return [path.as_uri(), match[1]]
    pytest.fail(f"Installed gateway no longer exports {name}; update the source probe")


@pytest.fixture
def installed_gateway(tmp_path):
    executable = shutil.which("openclaw")
    node = shutil.which("node")
    if not executable or not node:
        pytest.skip("Installed OpenClaw and Node are needed for the source-backed probe")
    root = Path(executable).resolve().parent / "dist"
    ai = root.parent / "node_modules/@openclaw/ai/dist"
    modules = {
        "modelInput": _module_export(root, "model.inline-provider-*.mjs", "resolveProviderModelInput"),
        "images": _module_export(root, "images-*.mjs", "detectAndLoadPromptImages"),
        "responses": _module_export(ai, "openai-responses-shared-*.mjs", "convertResponsesMessages"),
    }
    http_sources = list(root.glob("openai-http-*.mjs"))
    assert len(http_sources) == 1

    def project(requests, sol_input):
        # A fresh home prevents module initialization from using owner state.
        # Socket/fetch guards also make any accidental provider access fail.
        result = subprocess.run(
            [node, "--input-type=module", "-e", _GATEWAY_PROBE],
            input=json.dumps({"modules": modules, "httpSource": str(http_sources[0]),
                              "requests": requests, "solInput": sol_input}),
            text=True, capture_output=True, timeout=20, cwd=tmp_path,
            env={"PATH": str(Path(node).parent) + ":/usr/bin:/bin", "HOME": str(tmp_path),
                 "OPENCLAW_STATE_DIR": str(tmp_path),
                 "OPENCLAW_CONFIG_PATH": str(tmp_path / "missing.json")},
        )
        assert result.returncode == 0, result.stderr
        return json.loads(result.stdout)

    return project


_GATEWAY_PROBE = r"""
import fs from 'node:fs';
import path from 'node:path';
import net from 'node:net';
import tls from 'node:tls';
import vm from 'node:vm';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
const denyNetwork = () => { throw Error('Network access forbidden in gateway probe'); };
net.Socket.prototype.connect = denyNetwork;
tls.connect = denyNetwork;
globalThis.fetch = denyNetwork;
const fixture = JSON.parse(fs.readFileSync(0, 'utf8'));
const load = async ([url, symbol]) => (await import(url))[symbol];
const resolveInput = await load(fixture.modules.modelInput);
const loadImages = await load(fixture.modules.images);
const serialize = await load(fixture.modules.responses);

// Run the installed HTTP image parser and command builder without starting
// its server, authentication, agent runtime, or provider dependencies.
const source = fs.readFileSync(fixture.httpSource, 'utf8');
const dependency = async (name) => {
    for (const match of source.matchAll(/import \{([^}]+)\} from "([^"\n]+)";/g)) {
        for (const entry of match[1].split(',')) {
            const [symbol, local] = entry.trim().split(/\s+as\s+/);
            if ((local ?? symbol) === name) {
                return (await import(pathToFileURL(path.resolve(path.dirname(fixture.httpSource), match[2]))))[symbol];
            }
        }
    }
    throw Error(`Installed HTTP parser dependency changed: ${name}`);
};
const bindings = {AbortController};
for (const name of ['normalizeOptionalString', 'normalizeLowercaseStringOrEmpty',
                    'estimateBase64DecodedBytes', 'extractImageContentFromSource']) {
    bindings[name] = await dependency(name);
}
const context = vm.createContext(bindings);
const section = (start, end) => {
    const first = source.indexOf(start);
    const last = source.indexOf(end, first + start.length);
    if (first < 0 || last < 0) throw Error('Installed HTTP parser layout changed');
    return source.slice(first, last);
};
vm.runInContext([
    section('function asMessages(', 'function extractTextContent('),
    section('function resolveImageUrlPart(', 'function buildAgentPrompt('),
    section('function buildAgentCommandInput(', 'function extractClientToolsFromChatRequest('),
].join('\n'), context);
const result = [];
for (const request of fixture.requests) {
    const active = context.resolveActiveTurnContext(request.payload.messages);
    const incoming = await context.resolveImagesForRequest(active, {
        maxImageParts: 8, maxTotalImageBytes: 80 * 1024 * 1024,
        images: {allowUrl: false, allowedMimes: new Set(['image/png', 'image/jpeg']), maxBytes: 10 * 1024 * 1024},
    }, new AbortController().signal);
    const command = context.buildAgentCommandInput({
        prompt: {message: 'Synthetic image fixture', images: incoming},
        modelOverride: request.headers['x-openclaw-model'],
        sessionKey: request.headers['x-openclaw-session-key'],
    });
    const [provider, id] = command.model.split('/');
    const input = resolveInput({provider, modelId: id,
        input: provider === 'regolo' ? ['text', 'image'] : fixture.solInput ?? undefined});
    const model = {id, provider, api: 'openai-responses', input};
    const prepared = await loadImages({model, prompt: command.message,
        existingImages: command.images, workspaceDir: process.cwd()});
    const payload = serialize(model, {messages: [{role: 'user', timestamp: 1,
        content: [{type: 'text', text: command.message}, ...prepared.images]}]}, new Set());
    const images = payload.flatMap(message => (message.content ?? []).filter(part => part.type === 'input_image'));
    result.push({model: command.model, input, received: incoming.length,
        submitted: images.length, failures: prepared.failedMediaCount,
        hashes: images.map(part => createHash('sha256').update(Buffer.from(part.image_url.split(',')[1], 'base64')).digest('hex')),
        mimes: images.map(part => part.image_url.split(';')[0].slice(5))});
}
console.log(JSON.stringify(result));
"""


@pytest.mark.parametrize("sol_input, submitted", [(None, 0), (["text"], 0), (["text", "image"], 1)])
@pytest.mark.parametrize("streaming", [False, True])
@pytest.mark.parametrize("mime, photo", [("image/png", PNG), ("image/jpeg", JPEG)])
def test_installed_gateway_explains_web_mobile_image_difference(
    image_route, installed_gateway, monkeypatch, sol_input, submitted, streaming, mime, photo,
):
    """The same bytes reach HTTP; only declared input changes the provider payload."""
    requests = []
    client_type = httpx.AsyncClient

    def gateway(request):
        assert request.url == "https://image-gateway.fixture.invalid/v1/chat/completions"
        payload = json.loads(request.content)
        requests.append({"headers": dict(request.headers), "payload": payload})
        if payload["stream"]:
            return httpx.Response(200, text='data: {"choices":[{"delta":{"content":"Fixture"}}]}\n\ndata: [DONE]\n\n')
        return httpx.Response(200, json={"choices": [{"message": {"content": "Fixture"}}]})

    class Activity:
        def __init__(self, emit, session_key=None, **kwargs):
            self.session_key = session_key

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

    monkeypatch.setattr(brains.httpx, "AsyncClient", lambda *a, **kw: client_type(*a, **kw, transport=httpx.MockTransport(gateway)))
    monkeypatch.setattr(brains, "GatewayActivity", Activity)
    monkeypatch.setattr(brains.openclaw_config, "image_model", lambda: QWEN)

    async def check():
        async def emit(event):
            pass

        kwargs = {"images": [{"mime": mime, "data": base64.b64encode(photo).decode("ascii")}],
                  "session_key": "image-parity", "emit": emit if streaming else None}
        await brains.chat_openclaw("Synthetic fixture", "Synthetic system", [], **kwargs)
        options = mobile_api._turn_options.set({"model": SOL, "reasoning_effort": "high"})
        stale = mobile_routing._model.set("text/stale")
        try:
            await mobile_routing.chat_gateway("Synthetic fixture", "Synthetic system", [], **kwargs)
            assert mobile_routing.model_override() == "text/stale"
        finally:
            mobile_routing._model.reset(stale)
            mobile_api._turn_options.reset(options)

    asyncio.run(check())
    assert len(requests) == 2
    assert requests[0]["payload"] == requests[1]["payload"]
    assert requests[0]["headers"]["x-openclaw-model"] == QWEN
    assert requests[1]["headers"]["x-openclaw-model"] == SOL
    assert image_route == [("sessions.patch", {"key": "image-parity", "thinkingLevel": "high", "model": SOL})]
    image_url = requests[1]["payload"]["messages"][-1]["content"][1]["image_url"]["url"]
    assert base64.b64decode(image_url.split(",", 1)[1]) == photo
    projected = installed_gateway(requests, sol_input)
    digest = hashlib.sha256(photo).hexdigest()
    assert projected == [
        {"model": QWEN, "input": ["text", "image"], "received": 1, "submitted": 1,
         "failures": 0, "hashes": [digest], "mimes": [mime]},
        {"model": SOL, "input": sol_input or ["text"], "received": 1, "submitted": submitted,
         "failures": 0, "hashes": [digest] if submitted else [], "mimes": [mime] if submitted else []},
    ]
