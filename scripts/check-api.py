#!/usr/bin/env python3
"""Checks lib-api against the app it stands in for.

Extensions compile against lib-api but run against yomikku's own classes, so every public or protected member a
stub declares has to exist in the app with the same signature, or an extension fails with NoSuchMethodError on the
first call. This compares the two with javap. Members the app has and the stubs do not are fine.

usage: scripts/check-api.py <path to a built yomikku checkout>
"""
import os
import subprocess
import sys

STUB_CLASSES = "lib-api/build/intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes"
APP_CLASSES = [
    "source-api/build/intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug",
    "core/common/build/intermediates/runtime_library_classes_dir/debug/bundleLibRuntimeToDirDebug",
]
# Written for lib-api only; the app has its own.
STUB_ONLY = {"eu.kanade.tachiyomi.network.NetworkHelper", "eu.kanade.tachiyomi.network.AndroidCookieJar"}


def members(classpath, name):
    out = subprocess.run(
        ["javap", "-protected", "-s", "-cp", classpath, name],
        capture_output=True, text=True,
    )
    if out.returncode != 0:
        return None
    lines = out.stdout.splitlines()
    result = set()
    for i, line in enumerate(lines):
        if line.strip().startswith("descriptor:"):
            decl = lines[i - 1].strip()
            name_part = decl.split("(")[0].split()[-1]
            result.add((name_part, line.strip()))
    return result


def main():
    app = sys.argv[1]
    app_cp = ":".join(os.path.join(app, p) for p in APP_CLASSES)
    failures = 0
    checked = 0
    if not os.path.isdir(STUB_CLASSES):
        sys.exit(f"no compiled stubs at {STUB_CLASSES}; build :lib-api:assembleRelease first")
    for root, _, files in os.walk(STUB_CLASSES):
        for f in files:
            if not f.endswith(".class") or "$" in f and not f.endswith("Companion.class"):
                continue
            name = os.path.relpath(os.path.join(root, f), STUB_CLASSES)[:-6].replace("/", ".")
            if name.endswith(".R") or ".R$" in name or name.endswith("BuildConfig"):
                continue
            stub = members(STUB_CLASSES, name)
            checked += 1
            real = members(app_cp, name)
            if real is None:
                if name not in STUB_ONLY:
                    print(f"missing in app: {name}")
                    failures += 1
                continue
            for member in sorted(stub - real):
                # A stub's own private helpers never reach javap -protected; anything left is a real mismatch.
                print(f"{name}: {member[0]} {member[1]}")
                failures += 1
    if checked == 0:
        sys.exit("no stub classes found, so nothing was checked")
    if failures:
        print(f"{failures} mismatch(es)")
        sys.exit(1)
    print(f"lib-api matches the app ({checked} classes)")


main()
