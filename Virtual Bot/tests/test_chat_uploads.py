"""Upload metadata must survive a real chat request and reach the brain."""
from __future__ import annotations

import io
from pathlib import Path
import tempfile
from unittest.mock import AsyncMock, patch
import zipfile

import pytest
from fastapi.testclient import TestClient
import chat_attachments
import main


def test_uploaded_text_bytes_and_numeric_size_reach_the_brain():
    observed = []

    async def brain(message, history, **kwargs):
        observed.append(message)
        return 'Read the document.', 'idle', 'test', []

    with tempfile.TemporaryDirectory() as directory, patch.object(main.cfg, 'UPLOADS_DIR', Path(directory)), \
            patch.object(main.brains, 'chat', side_effect=brain), \
            patch.object(main, '_extract_and_save_facts'), TestClient(main.app) as client:
        content = b'Project deadline: October 15.\nOwner: Robin.'
        uploaded = client.post('/api/chat/upload', files={'file': ('notes.md', content, 'text/markdown')})
        assert uploaded.status_code == 200
        attachment = uploaded.json()
        assert attachment['size'] == len(content)
        assert attachment['name'] == 'notes.md'
        assert client.get(attachment['url']).content == content
        submitted = {**attachment, 'content': 'Client-supplied extracted content.'}
        response = client.post('/api/chat', json={'message': 'Read this.', 'attachments': [submitted]})
        assert response.status_code == 200, response.text
        # The background title request must not overwrite the actual turn.
        message = next(message for message in observed if message.startswith('Read this.'))
        assert 'October 15' in message
        assert 'Owner: Robin.' in message
        assert 'Client-supplied extracted content.' not in message
        stored = client.get('/api/sessions/' + response.json()['session_id']).json()
        user_message = next(item for item in stored['messages'] if item['role'] == 'user')
        assert user_message['attachments'][0]['size'] == len(content)
        assert 'content' not in user_message['attachments'][0]


def test_pdf_and_docx_extract_real_document_text(tmp_path):
    from pypdf import PdfWriter
    from pypdf.generic import NameObject, DictionaryObject, DecodedStreamObject
    writer = PdfWriter()
    page = writer.add_blank_page(width=300, height=200)
    font = DictionaryObject({NameObject('/Type'): NameObject('/Font'),
        NameObject('/Subtype'): NameObject('/Type1'), NameObject('/BaseFont'): NameObject('/Helvetica')})
    page[NameObject('/Resources')] = DictionaryObject({NameObject('/Font'): DictionaryObject({NameObject('/F1'): font})})
    stream = DecodedStreamObject()
    stream.set_data(b'BT /F1 12 Tf 20 20 Td (Real PDF content.) Tj ET')
    page[NameObject('/Contents')] = writer._add_object(stream)
    pdf = tmp_path / 'document.pdf'
    writer.write(pdf)
    assert 'Real PDF content.' in chat_attachments.extract_text(pdf)
    docx = tmp_path / 'document.docx'
    with zipfile.ZipFile(docx, 'w') as archive:
        archive.writestr('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>First paragraph.</w:t></w:r></w:p><w:p><w:r><w:t>Second paragraph.</w:t></w:r></w:p></w:body></w:document>')
    assert chat_attachments.extract_text(docx) == 'First paragraph.\nSecond paragraph.'


def test_failed_uploads_leave_no_partial_files(tmp_path):
    with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), patch.object(chat_attachments, 'MAX_BYTES', 10), TestClient(main.app) as client:
        for name, content, code in [('big.txt', b'x' * 11, 413), ('empty.txt', b'', 400),
                ('binary.txt', b'\0bad', 400), ('encoding.txt', b'\xffbad', 400),
                ('bad.exe', b'bad', 400), ('scan.pdf', b'not a PDF', 400), ('bad.docx', b'not a zip', 400)]:
            response = client.post('/api/chat/upload', files={'file': (name, content)})
            assert response.status_code == code, response.text
            assert list(tmp_path.iterdir()) == [], name


def test_duplicate_display_names_keep_distinct_files_and_upload_owners(tmp_path):
    with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), \
            patch.object(main, '_require_user', AsyncMock(return_value='alice')), TestClient(main.app) as client:
        first = client.post('/api/chat/upload', files={'file': ('same.txt', b'first')}).json()
        second = client.post('/api/chat/upload', files={'file': ('same.txt', b'second')}).json()
        assert first['url'] != second['url']
        assert client.get(first['url']).content == b'first'
        with patch.object(main, '_require_user', AsyncMock(return_value='bob')):
            assert client.get(first['url']).status_code == 404
            response = client.post('/api/chat', json={'message': 'Read.', 'attachments': [first]})
            assert response.status_code == 400
            assert response.json()['detail'] == 'attachment_forbidden'
        assert client.get('/uploads/..%2Fsecret.txt').status_code == 404


def test_context_ignores_client_supplied_content_and_rejects_symlinks(tmp_path):
    file = tmp_path / 'real.txt'
    file.write_text('Server content.', encoding='utf-8')
    context = chat_attachments.document_context(tmp_path, [{'url': '/uploads/real.txt', 'name': 'Real', 'content': 'Forged data.'}])
    assert 'Server content.' in context
    assert 'Forged data.' not in context
    link = tmp_path / 'linked.txt'
    link.symlink_to(file)
    try:
        chat_attachments.resolve_upload(tmp_path, '/uploads/linked.txt')
        assert False, 'symlinks must not become attachments'
    except chat_attachments.AttachmentError:
        pass


def test_context_validates_all_attachment_owners_before_exhausting_text_budget(tmp_path):
    """Vision attachments still need ownership checks after documents fill the budget."""
    own_name, _ = chat_attachments.upload_name('notes.txt', 'alice')
    other_name, _ = chat_attachments.upload_name('photo.png', 'bob')
    (tmp_path / own_name).write_text('Complete notes.', encoding='utf-8')
    (tmp_path / other_name).write_bytes(b'\x89PNG\r\n\x1a\n')
    attachments = [{'url': '/uploads/' + own_name}, {'url': '/uploads/' + other_name, 'type': 'image/png'}]
    with patch.object(chat_attachments, 'MAX_CONTEXT', 5):
        with pytest.raises(chat_attachments.AttachmentError, match='attachment_forbidden'):
            chat_attachments.document_context(tmp_path, attachments, 'alice')
        with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), \
                patch.object(main, '_require_user', AsyncMock(return_value='alice')), \
                patch.object(main.brains, 'chat') as brain, TestClient(main.app) as client:
            response = client.post('/api/chat', json={'message': 'Read.', 'attachments': attachments})
            assert response.status_code == 400
            assert response.json()['detail'] == 'attachment_forbidden'
            brain.assert_not_called()


def test_partial_and_omitted_documents_are_explicit_in_context(tmp_path):
    first = tmp_path / 'first.txt'
    second = tmp_path / 'second.txt'
    third = tmp_path / 'third.txt'
    first.write_text('First document content.', encoding='utf-8')
    second.write_text('Second document content.', encoding='utf-8')
    third.write_text('Third document content.', encoding='utf-8')
    with patch.object(chat_attachments, 'MAX_TEXT', 8), patch.object(chat_attachments, 'MAX_CONTEXT', 10):
        extracted = chat_attachments.extract_document(first)
        assert extracted.text == 'First do'
        assert extracted.truncated is True
        context = chat_attachments.document_context(tmp_path, [
            {'url': '/uploads/first.txt', 'name': 'First'},
            {'url': '/uploads/second.txt', 'name': 'Second'},
            {'url': '/uploads/third.txt', 'name': 'Third'},
        ])
        assert 'First do' in context
        assert context.count('Partial document extract') == 2
        assert 'Attached document: Third' in context
        assert 'Document omitted' in context
        assert 'Third document content.' not in context


def test_docx_preserves_word_breaks_tabs_and_visible_text(tmp_path):
    file = tmp_path / 'layout.docx'
    with zipfile.ZipFile(file, 'w') as archive:
        archive.writestr('word/document.xml', '''<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>Name</w:t><w:tab/><w:t>Robin</w:t><w:br/><w:t>Next line</w:t><w:cr/><w:t>Last line</w:t><w:instrText>INTERNAL FIELD</w:instrText><w:delText>Deleted words</w:delText></w:r></w:p></w:body></w:document>''')
    assert chat_attachments.extract_text(file) == 'Name\tRobin\nNext line\nLast line'


def test_long_display_names_preserve_a_supported_extension():
    stored, display = chat_attachments.upload_name('a' * 220 + '.DOCX', 'alice')
    assert len(display) == 180
    assert display.endswith('.DOCX')
    assert stored.endswith('.docx')
    assert stored.startswith(chat_attachments.owner_prefix('alice'))


def test_extraction_does_not_follow_a_replaced_upload_path(tmp_path):
    target = tmp_path / 'original.txt'
    target.write_text('Original.', encoding='utf-8')
    validated = chat_attachments.resolve_upload(tmp_path, '/uploads/original.txt')
    replacement = tmp_path / 'replacement.txt'
    replacement.write_text('Replacement.', encoding='utf-8')
    target.unlink()
    target.symlink_to(replacement)
    with pytest.raises(chat_attachments.AttachmentError):
        chat_attachments.extract_text(validated)


def test_filesystem_race_is_an_attachment_error(tmp_path):
    with patch.object(Path, 'is_symlink', return_value=False), \
            patch.object(Path, 'is_file', return_value=True), \
            patch.object(Path, 'stat', side_effect=FileNotFoundError):
        with pytest.raises(chat_attachments.AttachmentError, match='invalid_attachment'):
            chat_attachments.resolve_upload(tmp_path, '/uploads/disappeared.txt')


def test_binary_content_after_the_first_kilobyte_is_not_accepted(tmp_path):
    file = tmp_path / 'binary.txt'
    file.write_bytes(b'Readable prefix.' * 100 + b'\0Binary suffix.')
    with pytest.raises(chat_attachments.AttachmentError, match='unreadable_document'):
        chat_attachments.extract_text(file)


def test_pdf_limits_are_scoped_to_the_current_extraction(tmp_path):
    from pypdf import get_configuration
    file = tmp_path / 'limits.pdf'
    file.write_bytes(b'PDF fixture')
    previous = get_configuration()

    def reader(_source):
        configuration = get_configuration()
        assert configuration.zlib_maximum_output_length == chat_attachments.MAX_PDF_STREAM
        assert configuration.page_tree_maximum_entries == 1000
        assert configuration.xform_maximum_invocations_per_extraction == 100
        raise ValueError('Invalid PDF fixture')

    with patch('pypdf.PdfReader', side_effect=reader):
        with pytest.raises(chat_attachments.AttachmentError, match='unreadable_document'):
            chat_attachments.extract_text(file)
    assert get_configuration() is previous


def test_encrypted_pdf_upload_is_rejected_without_a_leftover(tmp_path):
    from pypdf import PdfWriter
    writer = PdfWriter()
    writer.add_blank_page(width=300, height=200)
    writer.encrypt('fixture-password')
    content = io.BytesIO()
    writer.write(content)
    with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), TestClient(main.app) as client:
        response = client.post('/api/chat/upload', files={'file': ('locked.pdf', content.getvalue(), 'application/pdf')})
        assert response.status_code == 400
        assert response.json()['detail'] == 'encrypted_document'
        assert list(tmp_path.iterdir()) == []


def test_exclusive_creation_failure_cannot_delete_an_existing_upload(tmp_path):
    existing = tmp_path / 'existing.txt'
    existing.write_bytes(b'Previous upload.')
    with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), \
            patch.object(chat_attachments, 'upload_name', return_value=(existing.name, 'notes.txt')), \
            TestClient(main.app, raise_server_exceptions=False) as client:
        response = client.post('/api/chat/upload', files={'file': ('notes.txt', b'New upload.')})
        assert response.status_code >= 400
        assert existing.read_bytes() == b'Previous upload.'
        assert list(tmp_path.iterdir()) == [existing]


def test_extraction_enforces_the_size_bound_without_relying_on_the_route(tmp_path):
    file = tmp_path / 'bounded.txt'
    file.write_text('Four', encoding='utf-8')
    with patch.object(chat_attachments, 'MAX_BYTES', 3):
        with pytest.raises(chat_attachments.AttachmentError):
            chat_attachments.extract_document(file)


def test_docx_xml_resource_bound_is_checked_before_parsing(tmp_path):
    file = tmp_path / 'oversized.docx'
    with zipfile.ZipFile(file, 'w') as archive:
        archive.writestr('word/document.xml', b' ' * (2 * 1024 * 1024 + 1))
    with patch.object(chat_attachments.ElementTree, 'fromstring') as parser:
        with pytest.raises(chat_attachments.AttachmentError, match='unreadable_document'):
            chat_attachments.extract_document(file)
        parser.assert_not_called()


def test_invalid_and_oversized_images_are_rejected_before_chat(tmp_path):
    """An uploaded image must not be silently discarded by the vision loader."""
    with patch.object(main.cfg, 'UPLOADS_DIR', tmp_path), \
            patch.object(main, '_VISION_MAX_BYTES', 10), TestClient(main.app) as client:
        invalid = client.post('/api/chat/upload', files={'file': ('invalid.png', b'not PNG', 'image/png')})
        assert invalid.status_code == 400
        assert invalid.json()['detail'] == 'invalid_image'
        assert list(tmp_path.iterdir()) == []
        oversized = client.post('/api/chat/upload', files={'file': ('large.png', b'\x89PNG\r\n\x1a\n' + b'data', 'image/png')})
        assert oversized.status_code == 413
        assert oversized.json()['detail'] == 'image_too_large'
        assert list(tmp_path.iterdir()) == []
