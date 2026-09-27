import unittest
import tempfile
import os
from pathlib import Path
from unittest.mock import patch, mock_open
import json
import sys

sys.path.insert(0, os.path.dirname(__file__))
import i18n_check

class TestCatalogs(unittest.TestCase):
    def test_extract_js_object_pairs(self):
        text = """
        const uk = {
            'a': 'value A',
            "b": "value B",
            `c`: `value C`,
            // comment
            /* multi
               comment */
            unquoted: 'val'
        };
        """
        pairs, dups = i18n_check.extract_js_object_pairs(text)
        self.assertEqual(len(pairs), 4)
        self.assertEqual(dict(pairs), {'a': 'value A', 'b': 'value B', 'c': 'value C', 'unquoted': 'val'})
        self.assertEqual(dups, [])

    def test_extract_js_duplicates(self):
        text = """{ 'a': '1', 'a': '2' }"""
        pairs, dups = i18n_check.extract_js_object_pairs(text)
        self.assertEqual(dups, ['a'])

    def test_extract_python_dict(self):
        text = """
STRINGS = {
    "uk": {
        "a": "text a",
        "b": "text b",
        "a": "text a2"
    },
    "en": {
        "a": "en a"
    }
}
"""
        u, e, d = i18n_check.extract_python_dict(text)
        self.assertEqual(d["uk"], ["a"])
        self.assertEqual(dict(u), {"a": "text a2", "b": "text b"})

    @patch('builtins.print')
    def test_check_catalog_file(self, mock_print):
        rc = [0]
        # parity test
        uk = [("k1", "v1"), ("k2", "v2")]
        en = [("k1", "v1")]
        i18n_check.check_catalog_file("test", uk, en, {}, rc)
        self.assertEqual(rc[0], 1)

        rc = [0]
        uk = [("k1", "v1")]
        en = [("k1", "v1"), ("k2", "v2")]
        i18n_check.check_catalog_file("test", uk, en, {}, rc)
        self.assertEqual(rc[0], 1)

        # Cyrillic in en
        rc = [0]
        uk = [("k1", "привіт")]
        en = [("k1", "привіт")]
        i18n_check.check_catalog_file("test", uk, en, {}, rc)
        self.assertEqual(rc[0], 1)

        # Placeholder mismatch
        rc = [0]
        uk = [("k1", "test {a}")]
        en = [("k1", "test {b}")]
        i18n_check.check_catalog_file("test", uk, en, {}, rc)
        self.assertEqual(rc[0], 1)

        # Duplicate keys
        rc = [0]
        uk = [("k1", "v1")]
        en = [("k1", "v1")]
        i18n_check.check_catalog_file("test", uk, en, {"uk": ["k1"]}, rc)
        self.assertEqual(rc[0], 1)

    @patch('builtins.print')
    def test_unknown_data_i18n_key(self, mock_print):
        # We can test this by running check_catalogs with a mocked html file or js file containing invalid keys.
        # But we don't have to test the whole directory scan if we just mock the rglob.
        # Mocking pathlib read_text for the specific files
        def mock_read_text(self, *args, **kwargs):
            if "i18n.js" in str(self):
                return 'const DICT = { uk: {}, en: {} };'
            elif str(self).endswith('.html'):
                return '<div data-i18n="bad.key"></div>'
            elif str(self).endswith('.js'):
                return 't("another.bad.key")'
            return ''

        with patch('i18n_check.Path.read_text', autospec=True, side_effect=mock_read_text):
            with patch('i18n_check.Path.rglob') as mock_rglob:
                with patch('i18n_check.Path.glob', return_value=[]):
                    with patch('i18n_check.Path.exists', return_value=True):
                        mock_rglob.side_effect = [
                            [Path('test.html')],
                            [Path('test.js')]
                        ]

                        class Args:
                            pass
                        rc = i18n_check.check_catalogs(Args())
                        self.assertEqual(rc, 1)


class TestDebt(unittest.TestCase):
    def test_count_debt_python(self):
        source = '''
"""
Docstring укр 1
"""
# comment укр 1
x = "string укр 1"
y = 'string укр 2'
# noqa: ... — укр
def f():
    """Docstring укр 2"""
    pass
        '''
        comments, strings = i18n_check.count_debt_python(source)
        # comments: 2, 5, 8, 10
        self.assertEqual(comments, 4)
        # strings: 6, 7
        self.assertEqual(strings, 2)

    def test_count_debt_js(self):
        source = '''
// comment укр 1
/*
  comment укр 2
*/
const a = "string укр 1";
const b = `
string укр 2
`;
<!-- html укр -->
        '''
        comments, strings = i18n_check.count_debt_js_html(source)
        # comments: 2, 4, 10
        self.assertEqual(comments, 3)
        # strings: 6, 8
        self.assertEqual(strings, 2)

    def test_count_debt_sh(self):
        source = '''
# comment укр 1
echo "string укр 1"
# comment укр 2
        '''
        comments, strings = i18n_check.count_debt_sh(source)
        self.assertEqual(comments, 2)
        self.assertEqual(strings, 0)

class TestRatchet(unittest.TestCase):
    @patch('i18n_check.check_debt')
    @patch('builtins.open', new_callable=mock_open, read_data='{"f1": {"comments": 1, "strings": 1, "total": 2}}')
    @patch('i18n_check.Path.exists', return_value=True)
    @patch('builtins.print')
    def test_ratchet_pass(self, mock_print, mock_exists, mock_file, mock_check_debt):
        mock_check_debt.return_value = {"f1": {"comments": 1, "strings": 1, "total": 2}}
        class Args:
            update = False
        self.assertEqual(i18n_check.check_ratchet(Args()), 0)

    @patch('i18n_check.check_debt')
    @patch('builtins.open', new_callable=mock_open, read_data='{"f1": {"comments": 1, "strings": 1, "total": 2}}')
    @patch('i18n_check.Path.exists', return_value=True)
    @patch('builtins.print')
    def test_ratchet_fail_increase(self, mock_print, mock_exists, mock_file, mock_check_debt):
        mock_check_debt.return_value = {"f1": {"comments": 2, "strings": 1, "total": 3}}
        class Args:
            update = False
        self.assertEqual(i18n_check.check_ratchet(Args()), 1)

    @patch('i18n_check.check_debt')
    @patch('builtins.open', new_callable=mock_open, read_data='{"f1": {"comments": 1, "strings": 1, "total": 2}}')
    @patch('i18n_check.Path.exists', return_value=True)
    @patch('builtins.print')
    def test_ratchet_fail_new_file(self, mock_print, mock_exists, mock_file, mock_check_debt):
        mock_check_debt.return_value = {
            "f1": {"comments": 1, "strings": 1, "total": 2},
            "f2": {"comments": 1, "strings": 0, "total": 1}
        }
        class Args:
            update = False
        self.assertEqual(i18n_check.check_ratchet(Args()), 1)

    @patch('i18n_check.check_debt')
    @patch('builtins.open', new_callable=mock_open, read_data='{"f1": {"comments": 2, "strings": 1, "total": 3}}')
    @patch('i18n_check.Path.exists', return_value=True)
    @patch('builtins.print')
    def test_ratchet_pass_decrease(self, mock_print, mock_exists, mock_file, mock_check_debt):
        mock_check_debt.return_value = {"f1": {"comments": 1, "strings": 1, "total": 2}}
        class Args:
            update = False
        self.assertEqual(i18n_check.check_ratchet(Args()), 0)

    @patch('i18n_check.check_debt')
    @patch('builtins.open', new_callable=mock_open, read_data='{"f1": {"comments": 1, "strings": 1, "total": 2}}')
    @patch('i18n_check.Path.exists', return_value=True)
    @patch('builtins.print')
    def test_ratchet_update(self, mock_print, mock_exists, mock_file, mock_check_debt):
        mock_check_debt.return_value = {"f1": {"comments": 2, "strings": 1, "total": 3}}
        class Args:
            update = True
        self.assertEqual(i18n_check.check_ratchet(Args()), 0)
        # Should be written
        mock_file().write.assert_called()

    @patch('i18n_check.check_debt')
    @patch('builtins.open', new_callable=mock_open, read_data='{"f1": {"comments": 1, "strings": 1, "total": 2}}')
    @patch('i18n_check.Path.exists', return_value=True)
    @patch('builtins.print')
    def test_ratchet_disagree_unchanged(self, mock_print, mock_exists, mock_file, mock_check_debt):
        # Baseline has f1=1,1, current has f1=2,2. It should fail according to user feedback:
        # "Add a test that fails if baseline and counter disagree on an unchanged file".
        # This is essentially the same as fail_increase, but explicit.
        mock_check_debt.return_value = {"f1": {"comments": 2, "strings": 2, "total": 4}}
        class Args:
            update = False
        self.assertEqual(i18n_check.check_ratchet(Args()), 1)

if __name__ == '__main__':
    unittest.main()
