"""Bounded uploaded documents, read from server files rather than client metadata."""
from __future__ import annotations

import hashlib
import io
import json
import mimetypes
import os
import re
import stat
import unicodedata
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree

MAX_BYTES = 20 * 1024 * 1024
MAX_TEXT = 40_000
MAX_CONTEXT = 60_000
MAX_PREVIEW = 4_000
MAX_NAME = 180
MAX_PDF_PAGES = 100
MAX_PDF_STREAM = 4 * 1024 * 1024
TEXT_SUFFIXES = {'.txt', '.md', '.json', '.csv', '.tsv', '.log', '.py', '.js', '.ts', '.tsx', '.jsx', '.html', '.css', '.yaml', '.yml', '.xml', '.sql'}
IMAGE_SUFFIXES = {'.png', '.jpg', '.jpeg', '.webp', '.gif'}
SUFFIXES = TEXT_SUFFIXES | IMAGE_SUFFIXES | {'.pdf', '.docx'}


class AttachmentError(ValueError):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class ExtractedDocument:
    text: str
    truncated: bool = False
    size: int = 0


def owner_prefix(user_id: str) -> str:
    return 'u' + hashlib.sha256(user_id.encode()).hexdigest()[:24] + '-' if user_id else 'local-'


def upload_name(name: str, user_id: str) -> tuple[str, str]:
    display = Path(name.replace('\\', '/')).name or 'upload'
    suffix = Path(display).suffix.lower()
    if suffix not in SUFFIXES:
        raise AttachmentError('unsupported_type')
    if len(display) > MAX_NAME:
        display = display[:MAX_NAME - len(suffix)] + display[-len(suffix):]
    return owner_prefix(user_id) + uuid.uuid4().hex + suffix, display


def resolve_upload(root: Path, url: str, user_id: str = '') -> Path:
    if not url.startswith('/uploads/'):
        raise AttachmentError('invalid_attachment')
    name = url[len('/uploads/'):]
    if not name or not re.fullmatch(r'[A-Za-z0-9_.-]{1,220}', name) or name in {'.', '..'}:
        raise AttachmentError('invalid_attachment')
    if user_id and not name.startswith(owner_prefix(user_id)):
        raise AttachmentError('attachment_forbidden')
    path = root / name
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_BYTES:
            raise AttachmentError('invalid_attachment')
        if not path.resolve().is_relative_to(root.resolve()):
            raise AttachmentError('invalid_attachment')
    except AttachmentError:
        raise
    except (OSError, ValueError) as exc:
        raise AttachmentError('invalid_attachment') from exc
    return path


def _document_bytes(path: Path) -> bytes:
    # Hold the opened file, rather than following a path changed after validation.
    try:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, 'rb') as document:
            info = os.fstat(document.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_BYTES:
                raise AttachmentError('invalid_attachment')
            data = document.read(MAX_BYTES + 1)
    except OSError as exc:
        raise AttachmentError('invalid_attachment') from exc
    if len(data) > MAX_BYTES:
        raise AttachmentError('invalid_attachment')
    return data


def extract_document(path: Path) -> ExtractedDocument:
    suffix = path.suffix.lower()
    try:
        data = _document_bytes(path)
        if suffix in TEXT_SUFFIXES:
            if b'\0' in data:
                raise AttachmentError('unreadable_document')
            text = data.decode('utf-8-sig')
            return ExtractedDocument(text[:MAX_TEXT], len(text) > MAX_TEXT, size=len(data))
        if suffix == '.pdf':
            from pypdf import PdfReader, apply_configuration
            # Context-local limits also apply when extraction runs in a worker
            # thread, without changing another concurrent PDF reader's settings.
            with apply_configuration(maximum_declared_stream_length=MAX_BYTES,
                    array_based_stream_maximum_output_length=MAX_PDF_STREAM,
                    zlib_maximum_output_length=MAX_PDF_STREAM,
                    lzw_maximum_output_length=MAX_PDF_STREAM,
                    run_length_maximum_output_length=MAX_PDF_STREAM,
                    page_tree_maximum_entries=1000, page_tree_maximum_depth=32,
                    xform_maximum_invocations_per_extraction=100):
                reader = PdfReader(io.BytesIO(data))
                if reader.is_encrypted:
                    raise AttachmentError('encrypted_document')
                text = ''
                pages = reader.pages
                count = len(pages)
                for index, page in enumerate(pages[:MAX_PDF_PAGES]):
                    text += (page.extract_text() or '') + '\n'
                    if len(text) > MAX_TEXT:
                        return ExtractedDocument(text[:MAX_TEXT], True, size=len(data))
                    if len(text) == MAX_TEXT:
                        return ExtractedDocument(text, index + 1 < count, size=len(data))
                return ExtractedDocument(text[:MAX_TEXT], count > MAX_PDF_PAGES, size=len(data))
        if suffix == '.docx':
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                entry = archive.getinfo('word/document.xml')
                if entry.file_size > 2 * 1024 * 1024:
                    raise AttachmentError('unreadable_document')
                xml = archive.read(entry)
                # Word documents do not need DTDs or expanded XML entities.
                if b'<!DOCTYPE' in xml or b'<!ENTITY' in xml:
                    raise AttachmentError('unreadable_document')
                tree = ElementTree.fromstring(xml)
            ns = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'
            paragraphs = []
            for paragraph in tree.iter(ns + 'p'):
                parts = []
                for node in paragraph.iter():
                    if node.tag == ns + 't':
                        parts.append(node.text or '')
                    elif node.tag in {ns + 'br', ns + 'cr'}:
                        parts.append('\n')
                    elif node.tag == ns + 'tab':
                        parts.append('\t')
                paragraphs.append(''.join(parts))
            text = '\n'.join(paragraphs)
            return ExtractedDocument(text[:MAX_TEXT], len(text) > MAX_TEXT, size=len(data))
    except AttachmentError:
        raise
    except Exception as exc:
        raise AttachmentError('unreadable_document') from exc
    return ExtractedDocument('', size=len(data))


def extract_text(path: Path) -> str:
    return extract_document(path).text


def _attachment_type(path: Path) -> str:
    if path.suffix.lower() not in SUFFIXES:
        raise AttachmentError('unsupported_type')
    return mimetypes.guess_type(path.name)[0] or 'application/octet-stream'


def attachment_preview(root: Path, url: str, user_id: str = '') -> dict:
    """Preview only server bytes; size comes from the same safely opened file."""
    path = resolve_upload(root, url, user_id)
    mime = _attachment_type(path)
    extracted = extract_document(path)
    if path.suffix.lower() not in IMAGE_SUFFIXES and not extracted.text.strip():
        raise AttachmentError('unreadable_document')
    return {
        'text': extracted.text[:MAX_PREVIEW],
        'truncated': extracted.truncated or len(extracted.text) > MAX_PREVIEW,
        'type': mime,
        'size': extracted.size,
    }


def _attachment_display_name(attachment: dict, path: Path) -> str:
    name = str(attachment.get('name') or path.name)[:MAX_NAME]
    # Controls, bidi overrides and Unicode line separators cannot create new
    # prompt records or hide the boundaries of a filename.
    return ''.join(' ' if unicodedata.category(char).startswith('C')
        or char in '\u2028\u2029' else char for char in name)


def _reference_json(value: str | dict) -> str:
    # Each record is an object (never an array), so escaping brackets here
    # only affects filenames. Role/content tags and fences stay literal data.
    return json.dumps(value, ensure_ascii=True).translate({
        ord(char): f'\\u{ord(char):04x}' for char in '<>&[]`'
    })


def attachment_manifest(root: Path, attachments: list[dict], user_id: str = '') -> str:
    """Ordered JSON records identify documents and the separately sent images."""
    records = []
    for position, attachment in enumerate(attachments[:8], start=1):
        path = resolve_upload(root, str(attachment.get('url') or ''), user_id)
        records.append(_reference_json({
            'position': position,
            'name': _attachment_display_name(attachment, path),
            'type': _attachment_type(path),
        }))
    return '\n'.join(records)


def document_context(root: Path, attachments: list[dict], user_id: str = '') -> str:
    blocks: list[str] = []
    remaining = MAX_CONTEXT
    # Validate every attachment before a text budget can omit later documents
    # or images that the separate vision loader will still read.
    files = [(attachment, resolve_upload(root, str(attachment.get('url') or ''), user_id))
        for attachment in attachments[:8]]
    for attachment, path in files:
        if path.suffix.lower() in IMAGE_SUFFIXES:
            continue
        name = _reference_json(_attachment_display_name(attachment, path))
        if not remaining:
            blocks.append(f'Attached document: {name}\n[Document omitted: the attachment text budget was exhausted.]')
            continue
        extracted = extract_document(path)
        text = extracted.text
        if not text.strip():
            raise AttachmentError('unreadable_document')
        excerpt = text[:remaining]
        partial = '\n[Partial document extract: additional content was omitted.]' if extracted.truncated or len(excerpt) < len(text) else ''
        blocks.append(f'Attached document: {name}\n<attachment-content>\n{excerpt}\n</attachment-content>{partial}')
        remaining -= len(excerpt)
    return '\n\n'.join(blocks)
