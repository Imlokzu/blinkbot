#!/usr/bin/env python3
"""
Scanner limitations:
- Python docstrings are identified reliably via AST, but any string assigned to a variable won't count as a docstring.
- JS/TS/HTML parsing uses a simplified regex token scanner. It might be confused by complex nested regex literals containing `//` or `/*`.
- Bash (.sh) files only check `#` comments and do not parse string literals.
"""
import argparse
import sys
import ast
import re
from pathlib import Path
import tokenize
import subprocess
import json
from io import BytesIO

def is_catalog_or_ignored(path_str):
    if "vendor/" in path_str or "node_modules/" in path_str:
        return True

    # Match exact catalogue files
    if path_str == "Virtual Bot/static/screen/i18n.js":
        return True
    if path_str == "Virtual Bot/dashboard/src/lib/i18n.ts":
        return True
    if path_str.startswith("Virtual Bot/dashboard/src/locales/") and path_str.endswith(".ts"):
        return True
    if path_str == "Virtual Bot/integrations/locales.py":
        return True

    if "tests/fixtures" in path_str or "test/fixtures" in path_str or "tests/data" in path_str:
        return True
    return False

# Python 3.12 split f-strings into FSTRING_START / FSTRING_MIDDLE / FSTRING_END
# (and 3.14 added the same for t-strings); before that an f-string was one
# STRING token. Matching only STRING made the count depend on the interpreter:
# on 3.12 the Ukrainian text inside f"..." vanished from the debt, so a
# baseline written on one version failed the ratchet on another.
STRING_TOKENS = {tokenize.STRING} | {
    getattr(tokenize, name) for name in ("FSTRING_MIDDLE", "TSTRING_MIDDLE") if hasattr(tokenize, name)
}

def count_debt_python(source):
    comments = set()
    strings = set()

    docstring_lines = set()
    try:
        module = ast.parse(source)
        for node in ast.walk(module):
            if isinstance(node, (ast.Module, ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
                if ast.get_docstring(node, clean=False) is not None:
                    doc_node = node.body[0]
                    for line in range(doc_node.lineno, doc_node.end_lineno + 1):
                        docstring_lines.add(line)
            elif isinstance(node, ast.Expr) and isinstance(node.value, ast.Constant) and isinstance(node.value.value, str):
                for line in range(node.lineno, node.end_lineno + 1):
                    docstring_lines.add(line)
    except Exception:
        pass

    try:
        for t in tokenize.tokenize(BytesIO(source.encode('utf-8')).readline):
            if t.type == tokenize.COMMENT:
                if re.search(r'[А-Яа-яІіЇїЄєҐґ]', t.string):
                    comments.add(t.start[0])
            elif t.type in STRING_TOKENS:
                if re.search(r'[А-Яа-яІіЇїЄєҐґ]', t.string):
                    lines = t.string.splitlines()
                    start_lineno = t.start[0]
                    for i, line in enumerate(lines):
                        if re.search(r'[А-Яа-яІіЇїЄєҐґ]', line):
                            lineno = start_lineno + i
                            if lineno in docstring_lines:
                                comments.add(lineno)
                            else:
                                strings.add(lineno)
    except tokenize.TokenError:
        pass

    return len(comments), len(strings)

def count_debt_js_html(source):
    comments = set()
    strings = set()

    tokens = re.finditer(
        r'(?P<SLCOMMENT>//.*?$)|'
        r'(?P<MLCOMMENT>/\*.*?\*/)|'
        r'(?P<HTMLCOMMENT><!--.*?-->)|'
        r'(?P<STR1>\'(?:\\.|[^\'\\])*\')|'
        r'(?P<STR2>"(?:\\.|[^"\\])*")|'
        r'(?P<STR3>`(?:\\.|[^`\\])*`)',
        source,
        re.MULTILINE | re.DOTALL
    )

    line_offsets = [0]
    for m in re.finditer(r'\n', source):
        line_offsets.append(m.start() + 1)

    def get_line_num(pos):
        for i, offset in enumerate(line_offsets):
            if pos < offset:
                return i
        return len(line_offsets)

    for match in tokens:
        kind = match.lastgroup
        value = match.group()

        if not re.search(r'[А-Яа-яІіЇїЄєҐґ]', value):
            continue

        start_pos = match.start()
        start_line = get_line_num(start_pos)

        lines = value.splitlines()
        for i, line in enumerate(lines):
            if re.search(r'[А-Яа-яІіЇїЄєҐґ]', line):
                lineno = start_line + i
                if kind in ("SLCOMMENT", "MLCOMMENT", "HTMLCOMMENT"):
                    comments.add(lineno)
                else:
                    strings.add(lineno)

    return len(comments), len(strings)

def count_debt_sh(source):
    comments = set()
    for i, line in enumerate(source.splitlines()):
        if re.match(r'^\s*#', line):
            if re.search(r'[А-Яа-яІіЇїЄєҐґ]', line):
                comments.add(i + 1)
    return len(comments), 0

def check_debt(args, return_data=False):
    exts = {".py", ".ts", ".tsx", ".js", ".mjs", ".html", ".css", ".go", ".sh"}

    try:
        output = subprocess.check_output(["git", "ls-files"]).decode("utf-8")
        files = output.splitlines()
    except Exception as e:
        print("Failed to run git ls-files:", e)
        return 1 if not return_data else {}

    results = {}

    total_comments = 0
    total_strings = 0

    for f in files:
        if any(f.endswith(ext) for ext in exts) and not is_catalog_or_ignored(f):
            path = Path(f)
            if not path.exists():
                continue

            content = path.read_text(encoding="utf-8")
            if not re.search(r'[А-Яа-яІіЇїЄєҐґ]', content):
                continue

            if f.endswith(".py"):
                c, s = count_debt_python(content)
            elif f.endswith(".sh"):
                c, s = count_debt_sh(content)
            else:
                c, s = count_debt_js_html(content)

            if c > 0 or s > 0:
                results[f] = {"comments": c, "strings": s, "total": c + s}
                total_comments += c
                total_strings += s

    if return_data:
        return results

    if getattr(args, 'json', False):
        print(json.dumps(results, indent=2, sort_keys=True))
    else:
        # Table format
        sorted_res = sorted(results.items(), key=lambda x: x[1]["total"], reverse=True)
        print(f"{'File':<60} | {'Comments':<8} | {'Strings':<8} | {'Total':<8}")
        print("-" * 91)
        for f, data in sorted_res:
            print(f"{f:<60} | {data['comments']:<8} | {data['strings']:<8} | {data['total']:<8}")
        print("-" * 91)
        print(f"{'Summary':<60} | {total_comments:<8} | {total_strings:<8} | {total_comments + total_strings:<8}")

    return 0

def extract_js_object_pairs(text):
    pairs = []
    duplicates = []
    seen_keys = set()

    tokens = re.finditer(
        r'(?P<SLCOMMENT>//.*?$)|'
        r'(?P<MLCOMMENT>/\*.*?\*/)|'
        r'(?P<STR1>\'(?:\\.|[^\'\\])*\')|'
        r'(?P<STR2>"(?:\\.|[^"\\])*")|'
        r'(?P<STR3>`(?:\\.|[^`\\])*`)|'
        r'(?P<IDENT>[a-zA-Z_$][a-zA-Z0-9_$.-]*)|'
        r'(?P<COLON>:)|'
        r'(?P<COMMA>,)|'
        r'(?P<LBRACE>\{)|'
        r'(?P<RBRACE>\})',
        text,
        re.MULTILINE | re.DOTALL
    )

    state = "EXPECT_KEY"
    current_key = None
    brace_depth = 0

    for match in tokens:
        kind = match.lastgroup
        value = match.group()

        if kind in ("SLCOMMENT", "MLCOMMENT"):
            continue

        if kind == "LBRACE":
            brace_depth += 1
            if brace_depth > 1 and state != "EXPECT_VALUE":
                continue
        elif kind == "RBRACE":
            brace_depth -= 1
            if brace_depth == 0:
                break

        if brace_depth == 1:
            if state == "EXPECT_KEY":
                if kind in ("IDENT", "STR1", "STR2", "STR3"):
                    if kind == "IDENT":
                        current_key = value
                    else:
                        current_key = value[1:-1]
                    state = "EXPECT_COLON"
            elif state == "EXPECT_COLON":
                if kind == "COLON":
                    state = "EXPECT_VALUE"
                else:
                    state = "EXPECT_KEY"
            elif state == "EXPECT_VALUE":
                if kind in ("STR1", "STR2", "STR3"):
                    val = value[1:-1].replace('\\"', '"').replace("\\'", "'").replace('\\n', '\n')
                    if current_key in seen_keys:
                        duplicates.append(current_key)
                    seen_keys.add(current_key)
                    pairs.append((current_key, val))
                    state = "EXPECT_COMMA"
            elif state == "EXPECT_COMMA":
                if kind == "COMMA":
                    state = "EXPECT_KEY"

    return pairs, duplicates

def extract_python_dict(text):
    duplicates = {"uk": [], "en": []}
    uk_pairs = []
    en_pairs = []

    try:
        module = ast.parse(text)
        for node in module.body:
            if isinstance(node, ast.Assign) or isinstance(node, ast.AnnAssign):
                target = node.targets[0] if isinstance(node, ast.Assign) else node.target
                if isinstance(target, ast.Name) and target.id == "STRINGS":
                    if isinstance(node.value, ast.Dict):
                        for key_node, val_node in zip(node.value.keys, node.value.values):
                            if isinstance(key_node, ast.Constant) and key_node.value == "uk":
                                if isinstance(val_node, ast.Dict):
                                    seen = set()
                                    for k, v in zip(val_node.keys, val_node.values):
                                        if isinstance(k, ast.Constant) and isinstance(v, ast.Constant):
                                            if k.value in seen:
                                                duplicates["uk"].append(k.value)
                                            seen.add(k.value)
                                            uk_pairs.append((k.value, v.value))
                            elif isinstance(key_node, ast.Constant) and key_node.value == "en":
                                if isinstance(val_node, ast.Dict):
                                    seen = set()
                                    for k, v in zip(val_node.keys, val_node.values):
                                        if isinstance(k, ast.Constant) and isinstance(v, ast.Constant):
                                            if k.value in seen:
                                                duplicates["en"].append(k.value)
                                            seen.add(k.value)
                                            en_pairs.append((k.value, v.value))
                        return uk_pairs, en_pairs, duplicates
    except Exception:
        pass
    return [], [], {"uk": [], "en": []}

def check_catalog_file(name, uk_pairs, en_pairs, duplicates, rc_ref):
    uk_dict = dict(uk_pairs)
    en_dict = dict(en_pairs)

    uk_keys = set(uk_dict.keys())
    en_keys = set(en_dict.keys())

    missing_in_en = uk_keys - en_keys
    missing_in_uk = en_keys - uk_keys

    has_error = False

    if duplicates.get("uk"):
        print(f"[{name}] Duplicate keys in uk: {', '.join(duplicates['uk'])}")
        has_error = True
    if duplicates.get("en"):
        print(f"[{name}] Duplicate keys in en: {', '.join(duplicates['en'])}")
        has_error = True

    if missing_in_en:
        print(f"[{name}] Keys missing in en: {', '.join(missing_in_en)}")
        has_error = True
    if missing_in_uk:
        print(f"[{name}] Keys extra in en (missing in uk): {', '.join(missing_in_uk)}")
        has_error = True

    common_keys = uk_keys & en_keys
    for k in common_keys:
        u_val = uk_dict[k]
        e_val = en_dict[k]

        if not u_val:
            print(f"[{name}] Empty uk value for key: {k}")
            has_error = True
        if not e_val:
            print(f"[{name}] Empty en value for key: {k}")
            has_error = True

        u_placeholders = set(re.findall(r'\{[a-zA-Z0-9_]+\}', u_val))
        e_placeholders = set(re.findall(r'\{[a-zA-Z0-9_]+\}', e_val))
        if u_placeholders != e_placeholders:
            print(f"[{name}] Placeholder mismatch for key: {k} (uk: {u_placeholders}, en: {e_placeholders})")
            has_error = True

        if re.search(r'[А-Яа-яІіЇїЄєҐґ]', e_val) and k not in ["kb.lang.uk"]:
            print(f"[{name}] Cyrillic found in en value for key: {k}")
            has_error = True

    if has_error:
        rc_ref[0] = 1

def check_catalogs(args):
    rc = [0]

    # 1. Dashboard catalogs
    dashboard_files = list(Path("Virtual Bot/dashboard/src/locales").glob("*.ts"))
    lib_i18n = Path("Virtual Bot/dashboard/src/lib/i18n.ts")
    if lib_i18n.exists():
        dashboard_files.append(lib_i18n)

    for file_path in dashboard_files:
        if not file_path.exists(): continue
        content = file_path.read_text(encoding="utf-8")
        uk_match = re.search(r'const\s+uk\s*(?:[:\w<>\s,]+)?=\s*\{', content)
        en_match = re.search(r'const\s+en\s*(?:[:\w<>\s,]+)?=\s*\{', content)
        if uk_match and en_match:
            uk_pairs, uk_dups = extract_js_object_pairs(content[uk_match.end()-1:])
            en_pairs, en_dups = extract_js_object_pairs(content[en_match.end()-1:])
            check_catalog_file(str(file_path), uk_pairs, en_pairs, {"uk": uk_dups, "en": en_dups}, rc)

    # 2. Integrations catalog
    integrations_path = Path("Virtual Bot/integrations/locales.py")
    if integrations_path.exists():
        u_pairs, e_pairs, d_dups = extract_python_dict(integrations_path.read_text(encoding="utf-8"))
        check_catalog_file(str(integrations_path), u_pairs, e_pairs, d_dups, rc)

    # 3. Screen catalog
    screen_path = Path("Virtual Bot/static/screen/i18n.js")
    if screen_path.exists():
        content = screen_path.read_text(encoding="utf-8")
        m = re.search(r'const\s+DICT\s*=\s*\{', content)
        if m:
            dict_text = content[m.end()-1:]
            uk_m = re.search(r'uk\s*:\s*\{', dict_text)
            en_m = re.search(r'en\s*:\s*\{', dict_text)
            if uk_m and en_m:
                uk_pairs, uk_dups = extract_js_object_pairs(dict_text[uk_m.end()-1:])
                en_pairs, en_dups = extract_js_object_pairs(dict_text[en_m.end()-1:])
                check_catalog_file(str(screen_path), uk_pairs, en_pairs, {"uk": uk_dups, "en": en_dups}, rc)

                screen_keys = set(dict(uk_pairs).keys())
                used_keys = set()

                # HTML usage
                for p in Path("Virtual Bot/static").rglob("*.html"):
                    html_content = p.read_text(encoding="utf-8")
                    for match in re.finditer(r'data-i18n(?:-[a-z]+)?="([^"]+)"', html_content):
                        used_keys.add(match.group(1))

                # JS usage
                for p in Path("Virtual Bot/static/screen").rglob("*.js"):
                    js_content = p.read_text(encoding="utf-8")
                    # Make sure it's the actual `t()` function, not `something.t()` or `createElement(...)` ending in t
                    for match in re.finditer(r'(?:^|[^a-zA-Z0-9_.])t\(\s*[\'"]([a-zA-Z0-9.-]+)[\'"]\s*(?:,|\))', js_content):
                        k = match.group(1)
                        if not k.startswith(".") and " " not in k:
                            used_keys.add(k)

                missing_from_dict = used_keys - screen_keys
                # Filter out dynamically built keys
                missing_from_dict = {k for k in missing_from_dict if k not in ["link.lost", "screen.", "store.err.", "store.cat.", "kb.lang.", "kb.enter.", "kb.key."]}

                if missing_from_dict:
                    print(f"[Screen] Unknown keys used in HTML/JS: {', '.join(missing_from_dict)}")
                    rc[0] = 1

    if rc[0] == 0:
        print("Catalogs OK")
    return rc[0]

def check_ratchet(args):
    baseline_path = Path("scripts/i18n-debt-baseline.json")

    class DummyArgs:
        json = False

    current_data = check_debt(DummyArgs(), return_data=True)
    if current_data == 1:
        return 1

    if getattr(args, 'update', False):
        with open(baseline_path, "w", encoding="utf-8") as f:
            json.dump(current_data, f, indent=2, sort_keys=True)
        print(f"Baseline updated at {baseline_path}")
        return 0

    if not baseline_path.exists():
        print(f"Error: Baseline file {baseline_path} not found. Run with --update first.")
        return 1

    with open(baseline_path, "r", encoding="utf-8") as f:
        baseline_data = json.load(f)

    has_error = False
    decreased = False

    for filepath, current in current_data.items():
        if filepath not in baseline_data:
            if current["total"] > 0:
                print(f"[Ratchet] Error: New file {filepath} introduced debt (comments: {current['comments']}, strings: {current['strings']}).")
                has_error = True
        else:
            base = baseline_data[filepath]
            if current["comments"] > base["comments"]:
                print(f"[Ratchet] Error: {filepath} comment debt increased from {base['comments']} to {current['comments']}.")
                has_error = True
            elif current["comments"] < base["comments"]:
                decreased = True

            if current["strings"] > base["strings"]:
                print(f"[Ratchet] Error: {filepath} string debt increased from {base['strings']} to {current['strings']}.")
                has_error = True
            elif current["strings"] < base["strings"]:
                decreased = True

    for filepath in baseline_data:
        if filepath not in current_data:
            decreased = True

    if has_error:
        print("\nRatchet failed: Debt went up. Please reduce Cyrillic strings/comments, or if unavoidable, update baseline.")
        return 1

    if decreased:
        print("Debt decreased! Consider running `python scripts/i18n_check.py ratchet --update` to lock in the new lower baseline.")
    else:
        print("Debt unchanged. Ratchet OK.")

    return 0

def check_docs(args):
    try:
        output = subprocess.check_output(["git", "ls-files"]).decode("utf-8")
        files = output.splitlines()
    except Exception as e:
        print("Failed to run git ls-files:", e)
        return 1

    allowlist = {
        "CLAUDE.md": "Tooling instructions allowed",
        "Virtual Bot/docs/SCREEN-PLATFORM.md": "Translation pending",
    }

    has_error = False
    strict = getattr(args, 'strict', False)

    for f in files:
        if f.endswith(".md"):
            if f in allowlist or f.startswith("Virtual Bot/skills/"):
                continue

            path = Path(f)
            if not path.exists():
                continue

            content = path.read_text(encoding="utf-8")
            clean_content = re.sub(r'«[^»]*»', '', content)

            if re.search(r'[А-Яа-яІіЇїЄєҐґ]', clean_content):
                if strict:
                    print(f"[Docs] Error: Cyrillic found in {f}")
                    has_error = True
                else:
                    print(f"[Docs] Warning: Cyrillic found in {f} (pending translation)")

    if has_error:
        print("\nDocs check failed: All tracked Markdown files must be English.")
        return 1

    return 0

def run_all(args):
    args.strict = False
    rc = 0

    res = check_catalogs(args)
    if res != 0: rc = res

    res = check_ratchet(args)
    if res != 0: rc = res

    res = check_docs(args)
    if res != 0: rc = res

    return rc

def main():
    parser = argparse.ArgumentParser(description="i18n checker and debt ratchet.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    parser_catalogs = subparsers.add_parser("catalogs", help="Check translation catalogs")
    parser_catalogs.set_defaults(func=check_catalogs)

    parser_debt = subparsers.add_parser("debt", help="Calculate current translation debt")
    parser_debt.add_argument("--json", action="store_true", help="Output JSON format")
    parser_debt.set_defaults(func=check_debt)

    parser_ratchet = subparsers.add_parser("ratchet", help="Check debt against baseline")
    parser_ratchet.add_argument("--update", action="store_true", help="Update the baseline file")
    parser_ratchet.set_defaults(func=check_ratchet)

    parser_docs = subparsers.add_parser("docs", help="Check markdown documentation for Cyrillic")
    parser_docs.add_argument("--strict", action="store_true", help="Fail if not-allowlisted documentation contains Cyrillic")
    parser_docs.set_defaults(func=check_docs)

    parser_all = subparsers.add_parser("all", help="Run catalogs, ratchet, and docs checks")
    parser_all.set_defaults(func=run_all)

    args = parser.parse_args()
    try:
        sys.exit(args.func(args))
    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(2)

if __name__ == "__main__":
    main()
