"""Reproduce package/document observations with synthetic files and no network."""

import atexit
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import types
from unittest.mock import patch
import zipfile


REPOSITORY = Path(__file__).resolve().parents[2]
sys.dont_write_bytecode = True
if sys.platform == "darwin":
    subprocess.run(
        ["osascript", "-e", "set volume output muted true"],
        check=True, capture_output=True, timeout=5,
    )

environment = {key: os.environ[key] for key in ("PATH", "TMPDIR", "LANG", "LC_ALL")
               if key in os.environ}
os.environ.clear()
os.environ.update(environment)
fixture = tempfile.TemporaryDirectory(prefix="blink-audit-2026-10-08-")
atexit.register(fixture.cleanup)
ROOT = Path(fixture.name)


def guard_io(event, args):
    if event == "open" and isinstance(args[0], (str, bytes, os.PathLike)):
        name = Path(os.fsdecode(args[0])).name
        if name == ".env" or name.startswith(".env."):
            raise FileNotFoundError("Dotenv reads are disabled for this audit")
    if event in {"socket.connect", "socket.getaddrinfo", "socket.bind"}:
        raise OSError("Network operations are disabled for this audit")


sys.addaudithook(guard_io)
# screen_store needs only STORE_DIR. Do not import the real configuration,
# registry, application lifespan, integrations, or any owner runtime data.
configuration = types.ModuleType("app_config")
configuration.STORE_DIR = ROOT / "store"
sys.modules["app_config"] = configuration
sys.path.insert(0, str(REPOSITORY / "Virtual Bot"))
import chat_attachments
import screen_store


def archive(manifest, extra=None):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_STORED) as bundle:
        bundle.writestr("package.json", json.dumps(manifest))
        for name, value in (extra or {}).items():
            bundle.writestr(name, value)
    return buffer.getvalue()


def app_manifest(identifier, version="1"):
    return {"id": identifier, "type": "app", "version": version, "entry": "index.html"}


def orphan_trust():
    identifier = "type-switch-fixture"
    screen_store.import_archive(archive(app_manifest(identifier),
        {"index.html": "<p>Inert synthetic app</p>"}), install_now=True)
    before = screen_store.shared_app_csp(identifier + "/index.html")
    skin = {"id": identifier, "type": "skin", "version": "2", "vars": {"--bg": "#112233"}}
    update = screen_store.import_archive(archive(skin), install_now=False)
    screen_store.remove_shared(identifier)
    installed = screen_store.installed_dir("apps") / identifier / "index.html"
    after = screen_store.shared_app_csp(identifier + "/index.html")
    assert before and not update["installed"] and installed.is_file() and after is None
    return {"initial_sandbox_policy": bool(before), "type_change_accepted": True,
            "old_html_remains_after_remove": installed.is_file(),
            "policy_after_remove": after}


def import_rollback():
    identifier = "rollback-fixture"
    screen_store.import_archive(archive(app_manifest(identifier),
        {"index.html": "old inert bytes"}), install_now=True)
    original_rename = Path.rename

    def fail_new_source(path, target):
        if path.parent == screen_store.shared_dir() and path.name.startswith(f".{identifier}-") \
                and path.name != f".{identifier}-old":
            raise OSError("Synthetic promotion failure")
        return original_rename(path, target)

    with patch.object(Path, "rename", fail_new_source):
        try:
            screen_store.import_archive(archive(app_manifest(identifier, "2"),
                {"index.html": "new inert bytes"}), install_now=False)
        except screen_store.StoreError as error:
            code = error.code
        else:
            raise AssertionError("Expected promotion to fail")
    final = screen_store.shared_dir() / identifier
    previous = screen_store.shared_dir() / f".{identifier}-old"
    assert code == "io_error" and not final.exists() and previous.is_dir()
    assert screen_store.load_manifest(identifier) is None
    return {"error_code": code, "catalog_source_exists": final.exists(),
            "hidden_backup_exists": previous.is_dir(),
            "installed_html_exists": (screen_store.installed_dir("apps") / identifier / "index.html").is_file(),
            "sandbox_policy_after_failure": screen_store.shared_app_csp(identifier + "/index.html")}


def corrupt_member():
    data = bytearray(archive(app_manifest("crc-fixture"), {"index.html": "inert bytes"}))
    with zipfile.ZipFile(io.BytesIO(data)) as bundle:
        info = bundle.getinfo("index.html")
        offset = info.header_offset + 30 + len(info.filename.encode()) + len(info.extra)
    data[offset] ^= 1  # Preserve ZIP structure; invalidate only this entry's CRC.
    try:
        screen_store.inspect_archive(bytes(data))
    except Exception as error:
        result = {"exception_type": type(error).__name__,
                  "is_handled_store_error": isinstance(error, screen_store.StoreError)}
    else:
        raise AssertionError("Expected CRC failure")
    assert result == {"exception_type": "BadZipFile", "is_handled_store_error": False}
    return result


def export_size_contract():
    identifier = "export-fixture"
    source = screen_store.packages_dir() / identifier
    source.mkdir(parents=True)
    (source / "package.json").write_text(json.dumps(app_manifest(identifier)), encoding="utf-8")
    (source / "index.html").write_text("<p>Inert fixture</p>", encoding="utf-8")
    (source / "payload.txt").write_bytes(b"x" * (screen_store.MAX_UNPACKED_BYTES + 1))
    filename, data = screen_store.pack(identifier)
    try:
        screen_store.inspect_archive(data)
    except screen_store.StoreError as error:
        code = error.code
    else:
        raise AssertionError("Expected importer to reject oversized payload")
    assert code == "too_large" and len(data) < screen_store.MAX_ARCHIVE_BYTES
    return {"export_returned_archive": filename.endswith(".cbp"), "compressed_bytes": len(data),
            "payload_bytes": screen_store.MAX_UNPACKED_BYTES + 1, "import_error_code": code}


def docx_encoding():
    xml = ('<?xml version="1.0" encoding="{encoding}"?>'
           '<!DOCTYPE w:document [<!ENTITY note "synthetic-entity-text">]>'
           '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
           '<w:body><w:p><w:r><w:t>' + "&note;" * 32 + '</w:t></w:r></w:p></w:body></w:document>')
    observations = {}
    for encoding in ("UTF-8", "UTF-16"):
        data = xml.format(encoding=encoding).encode(encoding)
        document = ROOT / (encoding + ".docx")
        document.write_bytes(archive({}, {"word/document.xml": data}))
        try:
            extracted = chat_attachments.extract_document(document)
            observations[encoding] = {"accepted": True, "extracted_characters": len(extracted.text)}
            assert extracted.text == "synthetic-entity-text" * 32
        except chat_attachments.AttachmentError as error:
            observations[encoding] = {"accepted": False, "error_code": error.code}
    assert not observations["UTF-8"]["accepted"] and observations["UTF-16"]["accepted"]
    return observations


def main():
    observations = {
        "package_orphan_trust": orphan_trust(),
        "package_import_rollback": import_rollback(),
        "package_crc_error": corrupt_member(),
        "package_export_limits": export_size_contract(),
        "docx_encoding_policy": docx_encoding(),
    }
    print(json.dumps(observations, indent=2, sort_keys=True))
    fixture.cleanup()


if __name__ == "__main__":
    main()
