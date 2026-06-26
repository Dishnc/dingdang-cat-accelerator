#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DdmNG APP LegacyXTLS V1.2.0.4 hidden backend base fix.

Purpose:
1) Fix V1.2.0.3 Kotlin compile failure caused by UI statements appended outside
   DingdangLoginActivity class.
2) Enforce fixed default backend base: https://buy.aisuper.top
3) Hide obvious backend/server-base input widgets when they were left visible.

This script is intentionally conservative: it backs up the target file first and
only edits DingdangLoginActivity.kt.
"""
from __future__ import annotations

import datetime as _dt
import pathlib
import re
import shutil
import sys
from typing import Optional, Tuple

DEFAULT_BACKEND = "https://buy.aisuper.top"
DISPLAY_TEXT = "服务域名：https://buy.aisuper.top"
TARGET_RELATIVE_CANDIDATES = [
    pathlib.Path("V2rayNG/app/src/main/kotlin/com/v2ray/ang/ui/DingdangLoginActivity.kt"),
    pathlib.Path("V2rayNG/app/src/main/java/com/v2ray/ang/ui/DingdangLoginActivity.kt"),
    pathlib.Path("app/src/main/kotlin/com/v2ray/ang/ui/DingdangLoginActivity.kt"),
    pathlib.Path("app/src/main/java/com/v2ray/ang/ui/DingdangLoginActivity.kt"),
]


def _read_text(path: pathlib.Path) -> str:
    data = path.read_bytes()
    for enc in ("utf-8-sig", "utf-8", "gbk"):
        try:
            return data.decode(enc)
        except UnicodeDecodeError:
            pass
    return data.decode("utf-8", errors="replace")


def _write_text(path: pathlib.Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", newline="\n")


def find_target(repo: pathlib.Path) -> pathlib.Path:
    for rel in TARGET_RELATIVE_CANDIDATES:
        p = repo / rel
        if p.exists():
            return p
    hits = list(repo.rglob("DingdangLoginActivity.kt"))
    if hits:
        # Prefer the v2ray path if multiple are found.
        hits.sort(key=lambda p: ("com" not in str(p).replace("\\", "/"), len(str(p))))
        return hits[0]
    raise FileNotFoundError("Cannot find DingdangLoginActivity.kt under repository root")


def find_class_end(text: str) -> Optional[int]:
    m = re.search(r"\bclass\s+DingdangLoginActivity\b", text)
    if not m:
        return None
    brace = text.find("{", m.end())
    if brace < 0:
        return None

    depth = 0
    i = brace
    n = len(text)
    state = "normal"
    escaped = False

    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        tri = text[i:i + 3]

        if state == "normal":
            if tri == '"""':
                state = "raw_string"
                i += 3
                continue
            if ch == '"':
                state = "string"
                escaped = False
                i += 1
                continue
            if ch == "'":
                state = "char"
                escaped = False
                i += 1
                continue
            if ch == "/" and nxt == "/":
                state = "line_comment"
                i += 2
                continue
            if ch == "/" and nxt == "*":
                state = "block_comment"
                i += 2
                continue
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    return i
            i += 1
            continue

        if state == "string":
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                state = "normal"
            i += 1
            continue

        if state == "char":
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == "'":
                state = "normal"
            i += 1
            continue

        if state == "raw_string":
            if tri == '"""':
                state = "normal"
                i += 3
            else:
                i += 1
            continue

        if state == "line_comment":
            if ch in "\r\n":
                state = "normal"
            i += 1
            continue

        if state == "block_comment":
            if ch == "*" and nxt == "/":
                state = "normal"
                i += 2
            else:
                i += 1
            continue

    return None


def trim_broken_trailing_top_level_code(text: str) -> Tuple[str, bool, str]:
    end = find_class_end(text)
    if end is None:
        return text, False, "class end not found"
    tail = text[end + 1:]
    if not tail.strip():
        return text, False, "no trailing code"

    suspicious = (
        "statusText" in tail
        or "connectionBadge" in tail
        or "connectionSubText" in tail
        or "Dingdang" in tail
        or "LinearLayout" in tail
        or "TextView" in tail
        or "resources" in tail
        or "accent" in tail
        or len(tail.strip().splitlines()) >= 3
    )
    if suspicious:
        return text[:end + 1].rstrip() + "\n", True, f"removed {len(tail.splitlines())} trailing line(s) after class end"
    return text, False, "trailing code exists but was not suspicious; left unchanged"


def ensure_constants(text: str) -> Tuple[str, bool]:
    if "DINGDANG_FIXED_BACKEND_BASE" in text:
        return text, False

    end = find_class_end(text)
    if end is None:
        return text, False

    constants = f'''
        // DdmNG V1.2.0.4: backend base is fixed and hidden from users.
        private const val DINGDANG_FIXED_BACKEND_BASE = "{DEFAULT_BACKEND}"
        private const val DINGDANG_BACKEND_DISPLAY_TEXT = "{DISPLAY_TEXT}"
'''

    # Insert into an existing companion object inside this class when possible.
    class_text = text[:end]
    comp = re.search(r"companion\s+object\s*\{", class_text)
    if comp:
        insert_at = comp.end()
        return text[:insert_at] + constants + text[insert_at:], True

    block = "\n    companion object {" + constants + "    }\n"
    return text[:end].rstrip() + block + text[end:], True


def replace_backend_assignments(text: str) -> Tuple[str, int]:
    changed = 0
    original = text

    # Replace common backend/base-url variable assignments with the fixed default.
    # Avoid touching general variables like loginUrl or requestUrl.
    name = r"(?:backendBase|backendUrl|backendBaseUrl|baseUrl|apiBaseUrl|apiBase|serviceBase|serviceBaseUrl|serverBase|serverBaseUrl|backendHost|apiHost)"
    pattern = re.compile(
        rf"(?m)^(\s*(?:private\s+|internal\s+|protected\s+)?(?:val|var)\s+({name})\s*(?::\s*String)?\s*=\s*)(?!DINGDANG_FIXED_BACKEND_BASE\b).+$"
    )

    def repl(m: re.Match) -> str:
        nonlocal changed
        line = m.group(0)
        # Skip display text labels or URLs constructed from another fixed constant.
        if "DINGDANG_BACKEND_DISPLAY_TEXT" in line or "DISPLAY" in line.upper():
            return line
        changed += 1
        return m.group(1) + "DINGDANG_FIXED_BACKEND_BASE"

    text = pattern.sub(repl, text)

    # Replace empty string defaults in obvious backend preference reads.
    empty_default = re.compile(
        r"(getString\s*\(\s*[^\n;]*(?:backend|base_url|baseUrl|server|service)[^\n;]*,\s*)\"\"(\s*\))",
        re.IGNORECASE,
    )
    text, n = empty_default.subn(r"\1DINGDANG_FIXED_BACKEND_BASE\2", text)
    changed += n

    # Replace literal blank backend property defaults.
    literal_blank = re.compile(
        r"(?m)^(\s*(?:private\s+)?(?:val|var)\s+\w*(?:Backend|BaseUrl|ServerBase|ServiceBase)\w*\s*(?::\s*String)?\s*=\s*)\"\""
    )
    text, n = literal_blank.subn(r"\1DINGDANG_FIXED_BACKEND_BASE", text)
    changed += n

    if text == original:
        return text, 0
    return text, changed


def disable_blank_backend_guards(text: str) -> Tuple[str, int]:
    lines = text.splitlines()
    changed = 0
    out = lines[:]
    guard_re = re.compile(r"if\s*\(.*(?:backend|baseUrl|apiBase|serverBase|serviceBase).*(?:isBlank|isEmpty|isNullOrBlank|isNullOrEmpty).*\)", re.IGNORECASE)
    for i, line in enumerate(lines):
        if not guard_re.search(line):
            continue
        window = "\n".join(lines[i:min(len(lines), i + 10)])
        if "后端服务地址未配置" in window or "服务地址未配置" in window or "backend" in window.lower():
            indent = re.match(r"\s*", line).group(0)
            out[i] = indent + "if (false) { // DdmNG V1.2.0.4: fixed backend base is always configured"
            changed += 1
    return "\n".join(out) + ("\n" if text.endswith("\n") else ""), changed


def hide_obvious_backend_inputs(text: str) -> Tuple[str, int]:
    lines = text.splitlines()
    changed = 0
    tokens = ("backend", "baseUrl", "base_url", "serverBase", "serviceBase", "apiBase")
    safe_visible_tokens = ("display", "domain", "serviceDomain", "backendDisplay", "DINGDANG_BACKEND_DISPLAY_TEXT")
    out = []
    for line in lines:
        low = line.lower()
        is_backend_line = any(t.lower() in low for t in tokens)
        is_display_line = any(t.lower() in low for t in safe_visible_tokens)
        new = line
        if is_backend_line and not is_display_line:
            if "visibility" in line and "View.VISIBLE" in line:
                new = line.replace("View.VISIBLE", "View.GONE")
            if "isVisible" in line and re.search(r"=\s*true\b", line):
                new = re.sub(r"=\s*true\b", "= false", line)
            if new != line:
                changed += 1
        out.append(new)
    return "\n".join(out) + ("\n" if text.endswith("\n") else ""), changed


def ensure_display_literal(text: str) -> Tuple[str, bool]:
    # If no visible domain text exists, keep the constant so future UI code can use it.
    # We do not inject a raw TextView here to avoid breaking existing custom layouts.
    return text, DISPLAY_TEXT in text


def main() -> int:
    repo = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()
    target = find_target(repo)
    text = _read_text(target).replace("\r\n", "\n").replace("\r", "\n")

    stamp = _dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    backup_dir = repo / "ddmng_patch_backups" / f"V1.2.0.4_{stamp}"
    backup_dir.mkdir(parents=True, exist_ok=True)
    backup_path = backup_dir / target.name
    shutil.copy2(target, backup_path)

    report = []

    text, trimmed, reason = trim_broken_trailing_top_level_code(text)
    report.append(f"tail_cleanup={trimmed}: {reason}")

    text, added_constants = ensure_constants(text)
    report.append(f"constants_added={added_constants}")

    text, assign_changes = replace_backend_assignments(text)
    report.append(f"backend_assignment_changes={assign_changes}")

    text, guard_changes = disable_blank_backend_guards(text)
    report.append(f"blank_backend_guards_disabled={guard_changes}")

    text, hide_changes = hide_obvious_backend_inputs(text)
    report.append(f"backend_input_visibility_changes={hide_changes}")

    _, has_display = ensure_display_literal(text)
    report.append(f"display_text_present={has_display}")

    # Final sanity: ensure the class braces are balanced enough for the main class.
    if find_class_end(text) is None:
        raise RuntimeError("DingdangLoginActivity class brace matching failed after patch; backup preserved")

    _write_text(target, text)

    manifest = backup_dir / "patch_report.txt"
    manifest.write_text(
        "DdmNG APP LegacyXTLS V1.2.0.4 hidden backend base patch\n"
        f"target={target}\n"
        f"backup={backup_path}\n"
        + "\n".join(report)
        + "\n",
        encoding="utf-8",
    )

    print("[OK] Patched:", target)
    print("[OK] Backup:", backup_path)
    for item in report:
        print("[INFO]", item)
    print("[INFO] Fixed backend base:", DEFAULT_BACKEND)
    print("[INFO] User-facing backend input remains hidden when matching known backend/base-url widgets.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print("[ERROR]", exc, file=sys.stderr)
        raise SystemExit(1)
