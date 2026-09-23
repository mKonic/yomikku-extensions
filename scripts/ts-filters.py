#!/usr/bin/env python3
"""Extracts the filters of a standalone LNReader plugin as the JSON lib/lnfilters reads.

Standalone plugins declare their filters inline (`filters = { ... } satisfies Filters;`) instead of in a JSON file
beside them. This finds that object literal and turns it into JSON: FilterTypes members become their string values,
keys and single-quoted strings get double quotes, trailing commas go.

usage: scripts/ts-filters.py <plugin.ts> [out.json]
"""
import json
import re
import sys

FILTER_TYPES = {
    "TextInput": "Text", "Picker": "Picker", "CheckboxGroup": "Checkbox", "Switch": "Switch",
    "ExcludableCheckboxGroup": "XCheckbox",
}
# `filters = {` or `filters: Filters = {`.
FILTERS = r"\bfilters\s*(?::\s*[\w<>| ]+)?=\s*\{"
ESCAPES = {"n": "\n", "t": "\t", "r": "\r"}


def object_literal(source, start):
    """The text of the {...} that opens at [start], skipping braces inside strings and comments."""
    depth = 0
    i = start
    while i < len(source):
        c = source[i]
        if c in "'\"`":
            end = i + 1
            while source[end] != c:
                end += 2 if source[end] == "\\" else 1
            i = end
        elif source.startswith("//", i):
            i = source.index("\n", i)
        elif source.startswith("/*", i):
            i = source.index("*/", i) + 1
        elif c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
        i += 1
    raise ValueError("unbalanced braces")


def to_json(literal):
    out = []
    i = 0
    while i < len(literal):
        c = literal[i]
        if c in "'\"`":
            end = i + 1
            chars = []
            while literal[end] != c:
                if literal[end] == "\\":
                    n = literal[end + 1]
                    if n == "u":
                        chars.append(chr(int(literal[end + 2:end + 6], 16)))
                        end += 6
                        continue
                    chars.append(ESCAPES.get(n, n))
                    end += 2
                else:
                    chars.append(literal[end])
                    end += 1
            out.append(json.dumps("".join(chars), ensure_ascii=False))
            i = end + 1
            continue
        if literal.startswith("//", i):
            i = literal.index("\n", i)
            continue
        if literal.startswith("/*", i):
            i = literal.index("*/", i) + 2
            continue
        m = re.match(r"FilterTypes\.(\w+)", literal[i:])
        if m:
            out.append(json.dumps(FILTER_TYPES[m.group(1)]))
            i += m.end()
            continue
        m = re.match(r"([A-Za-z_$][\w$]*)\s*:", literal[i:])
        if m and (not out or out[-1].rstrip()[-1:] in "{,"):
            out.append(json.dumps(m.group(1)) + ":")
            i += m.end()
            continue
        out.append(c)
        i += 1
    text = "".join(out)
    text = re.sub(r",(\s*[}\]])", r"\1", text)
    return json.loads(text)


def main():
    source = open(sys.argv[1]).read()
    match = re.search(FILTERS, source)
    if not match:
        sys.exit("no filters in " + sys.argv[1])
    filters = to_json(object_literal(source, match.end() - 1))
    text = json.dumps(filters, ensure_ascii=False, indent=2) + "\n"
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)
    else:
        sys.stdout.write(text)


main()
