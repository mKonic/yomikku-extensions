#!/usr/bin/env python3
"""Sets up src/<lang>/<id> for a port of a standalone LNReader plugin: build script, icon and filters.

The source class itself is written by hand; this only does the parts every port shares.

usage: scripts/new-standalone.py <lnreader-plugins checkout> <plugin .ts path, relative to plugins/> <lang> <id>
       <ClassName> <name> <baseUrl> [--libs lnfilters,wpcommon] [--nsfw]
"""
import argparse
import json
import os
import re
import subprocess
import sys

from PIL import Image


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("lnreader")
    parser.add_argument("plugin")
    parser.add_argument("lang")
    parser.add_argument("id")
    parser.add_argument("class_name")
    parser.add_argument("name")
    parser.add_argument("base_url")
    parser.add_argument("--libs", default="")
    parser.add_argument("--nsfw", action="store_true")
    args = parser.parse_args()

    out = os.path.join("src", args.lang, args.id)
    if os.path.exists(out):
        sys.exit(out + " exists")
    package = f"app.yomikku.extension.{args.lang}.{args.id}"
    os.makedirs(os.path.join(out, "src", *package.split(".")))

    plugin = os.path.join(args.lnreader, "plugins", args.plugin)
    source = open(plugin).read()
    libs = [lib for lib in args.libs.split(",") if lib]
    if re.search(r"\bfilters\s*(?::\s*[\w<>| ]+)?=\s*\{", source):
        os.makedirs(os.path.join(out, "resources"))
        script = os.path.join(os.path.dirname(os.path.abspath(__file__)), "ts-filters.py")
        subprocess.run([sys.executable, script, plugin, os.path.join(out, "resources", "filters.json")], check=True)
        if "lnfilters" not in libs:
            libs.insert(0, "lnfilters")

    # The plugin names its icon as "src/<lang>/<dir>/icon.png", under public/static.
    icon_path = next((line.split("'")[1] for line in source.splitlines() if "icon =" in line and "'" in line), None)
    icon = os.path.join(args.lnreader, "public", "static", icon_path) if icon_path else None
    if icon and os.path.exists(icon):
        os.makedirs(os.path.join(out, "res", "mipmap-xxxhdpi"))
        Image.open(icon).convert("RGBA").save(os.path.join(out, "res", "mipmap-xxxhdpi", "ic_launcher.png"), "PNG")
    else:
        print("no icon found:", icon)

    lines = [
        "plugins {", '    id("yomikku.extension")', "}", "", "yomikku {",
        f"    name = {json.dumps(args.name, ensure_ascii=False)}",
        f'    className = ".{args.class_name}"',
        "    versionCode = 1",
    ]
    if args.nsfw:
        lines.append("    nsfw = true")
    lines += [
        f"    source(name = {json.dumps(args.name, ensure_ascii=False)}, lang = {json.dumps(args.lang)}, "
        f"baseUrl = {json.dumps(args.base_url)})",
        "}",
    ]
    if libs:
        lines += ["", "dependencies {"] + [f'    implementation(project(":lib:{lib}"))' for lib in libs] + ["}"]
    open(os.path.join(out, "build.gradle.kts"), "w").write("\n".join(lines) + "\n")
    print("wrote", out, "- source class goes in", os.path.join(out, "src", *package.split("."), args.class_name + ".kt"))


main()
