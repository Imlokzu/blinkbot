"""The no-DTD contract must apply to UTF-16 Word XML as well as UTF-8."""
import zipfile

import pytest

from chat_attachments import AttachmentError, extract_document


@pytest.mark.parametrize("encoding", ["utf-8", "utf-16", "utf-16-le", "utf-16-be"])
@pytest.mark.parametrize("doctype", [
    '<!DOCTYPE w:document [<!ENTITY sample "fixture">]>',
    '<!DOCTYPE w:document SYSTEM "file:///nonexistent-fixture">',
])
def test_docx_rejects_declarations_in_all_supported_encodings(tmp_path, encoding, doctype):
    declaration_encoding = "utf-8" if encoding == "utf-8" else "utf-16"
    xml = (f'<?xml version="1.0" encoding="{declaration_encoding}"?>{doctype}'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:p><w:r><w:t>Inert fixture</w:t></w:r></w:p></w:document>')
    path = tmp_path / "fixture.docx"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("word/document.xml", xml.encode(encoding))
    with pytest.raises(AttachmentError) as error:
        extract_document(path)
    assert error.value.code == "unreadable_document"


@pytest.mark.parametrize("encoding", ["utf-8", "utf-16", "utf-16-le", "utf-16-be"])
def test_normal_docx_still_extracts_supported_encodings(tmp_path, encoding):
    declaration_encoding = "utf-8" if encoding == "utf-8" else "utf-16"
    xml = (f'<?xml version="1.0" encoding="{declaration_encoding}"?>'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
        '<w:p><w:r><w:t>Readable fixture</w:t><w:tab/><w:t>Next</w:t></w:r></w:p></w:document>')
    path = tmp_path / "fixture.docx"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("word/document.xml", xml.encode(encoding))
    assert extract_document(path).text == "Readable fixture\tNext"


def test_malformed_encoding_has_stable_error(tmp_path):
    path = tmp_path / "fixture.docx"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("word/document.xml", b'<?xml version="1.0" encoding="UTF-16"?><broken>')
    with pytest.raises(AttachmentError) as error:
        extract_document(path)
    assert error.value.code == "unreadable_document"
