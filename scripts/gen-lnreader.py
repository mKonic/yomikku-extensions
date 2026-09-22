#!/usr/bin/env python3
"""Generates extensions for the sites of an LNReader multisrc theme.

LNReader keeps each theme's sites as data (lnreader-plugins/plugins/multisrc/<theme>/sources.json, with filters and
icons beside it). For every site that is not marked down, this writes src/<lang>/<id>/ with a build script, a source
class extending the Kotlin port of the theme, the site's filters as a resource and its icon.

usage: scripts/gen-lnreader.py <lnreader-plugins checkout> <theme> <KotlinTheme> [--only id,id]
Existing extensions are left alone unless --force is given, so hand edits survive regeneration.
"""
import argparse
import json
import os
import re
import shutil

LANGS = {
    "English": "en", "Turkish": "tr", "Arabic": "ar", "Indonesian": "id", "Spanish": "es", "Thai": "th",
    "French": "fr", "Portuguese": "pt", "Korean": "ko", "Russian": "ru", "Chinese": "zh", "Japanese": "ja",
    "Vietnamese": "vi", "Polish": "pl", "Ukrainian": "uk", "Italian": "it", "German": "de", "Multi": "all",
}


def ident(text):
    result = re.sub(r"[^a-z0-9]", "", text.lower()) or "source"
    # Package segments must start with a letter.
    return ("n" + result) if result[0].isdigit() else result


def class_name(name):
    words = re.findall(r"[A-Za-z0-9]+", name)
    result = "".join(w[:1].upper() + w[1:] for w in words)
    if not result or result[0].isdigit():
        result = "S" + result
    return result


def kotlin_string(value):
    return json.dumps(value)


def dead_sites(theme):
    """Sites of [theme] listed in scripts/dead-sites.txt as "<theme>/<id>  # why", which LNReader has not marked down."""
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dead-sites.txt")
    if not os.path.exists(path):
        return set()
    entries = (line.split("#", 1)[0].strip() for line in open(path))
    return {entry.split("/", 1)[1] for entry in entries if entry.startswith(theme + "/")}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("lnreader")
    parser.add_argument("theme")
    parser.add_argument("kotlin_theme")
    parser.add_argument("--only")
    parser.add_argument("--force", action="store_true")
    parser.add_argument("--option-args", default="",
                        help="comma separated option=kotlinParam pairs passed to the theme constructor")
    args = parser.parse_args()

    theme_dir = os.path.join(args.lnreader, "plugins", "multisrc", args.theme)
    icons_dir = os.path.join(args.lnreader, "public", "static", "multisrc", args.theme)
    sources = json.load(open(os.path.join(theme_dir, "sources.json")))
    only = set(args.only.split(",")) if args.only else None
    skip = dead_sites(args.theme)
    option_args = dict(pair.split("=") for pair in args.option_args.split(",") if pair)

    written = 0
    for source in sources:
        options = source.get("options", {})
        if options.get("down") or source["id"] in skip or (only and source["id"] not in only):
            continue
        lang = LANGS.get(options.get("lang", "English"), "all")
        ext_id = ident(source["id"])
        name = source["sourceName"]
        cls = class_name(name)
        base_url = source["sourceSite"].rstrip("/")
        out = os.path.join("src", lang, ext_id)
        if os.path.exists(out) and not args.force:
            continue
        package = f"app.yomikku.extension.{lang}.{ext_id}"

        os.makedirs(os.path.join(out, "src", *package.split(".")), exist_ok=True)
        extra = "".join(
            f", {param} = {json.dumps(options[opt])}"
            for opt, param in option_args.items() if opt in options
        )
        filters = os.path.join(theme_dir, "filters", source["id"] + ".json")
        has_filters = os.path.exists(filters)
        body = ""
        if has_filters:
            body = ' {\n    override val filtersResource = "filters.json"\n}'
        with open(os.path.join(out, "src", *package.split("."), cls + ".kt"), "w") as f:
            f.write(
                f"package {package}\n\n"
                f"import app.yomikku.multisrc.{args.theme}.{args.kotlin_theme}\n\n"
                f"class {cls} : {args.kotlin_theme}({kotlin_string(name)}, {kotlin_string(base_url)}, "
                f"{kotlin_string(lang)}{extra}){body}\n"
            )
        if has_filters:
            os.makedirs(os.path.join(out, "resources"), exist_ok=True)
            shutil.copy(filters, os.path.join(out, "resources", "filters.json"))
        icon = os.path.join(icons_dir, source["id"], "icon.png")
        if not os.path.exists(icon):
            icon = os.path.join(icons_dir, source["id"].lower(), "icon.png")
        if os.path.exists(icon):
            os.makedirs(os.path.join(out, "res", "mipmap-xxxhdpi"), exist_ok=True)
            shutil.copy(icon, os.path.join(out, "res", "mipmap-xxxhdpi", "ic_launcher.png"))
        else:
            print(f"no icon for {source['id']}")
        with open(os.path.join(out, "build.gradle.kts"), "w") as f:
            f.write(
                "plugins {\n    id(\"yomikku.extension\")\n}\n\n"
                "yomikku {\n"
                f"    name = {kotlin_string(name)}\n"
                f"    className = \".{cls}\"\n"
                "    versionCode = 1\n"
                f"    theme({kotlin_string(args.theme)})\n"
                f"    source(name = {kotlin_string(name)}, lang = {kotlin_string(lang)}, baseUrl = {kotlin_string(base_url)})\n"
                "}\n"
            )
        written += 1
    print(f"wrote {written} extension(s)")


main()
