#!/usr/bin/env python3
"""Builds the extension store from the release builds: repo/{apk,icon}/, index.min.json and repo.json.

Run after `./gradlew assembleRelease`. Every APK must be signed with the key the app trusts; a debug-signed build
fails the run instead of reaching the store.
"""
import glob
import hashlib
import json
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
REPO = os.path.join(ROOT, "repo")
FINGERPRINT = "34378863c3c0f4ce1afdbff10539450877733758f64832c07b8623e0f058dda8"
META = {
    "name": "Yomikku Extensions",
    "shortName": "yomikku",
    "website": "https://github.com/mKonic/yomikku-extensions",
    "signingKeyFingerprint": FINGERPRINT,
}


def source_id(name, lang, version_id):
    """Same as HttpSource.generateId: the first 8 bytes of md5("name/lang/versionId"), sign bit cleared."""
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    return int.from_bytes(digest[:8], "big") & 0x7FFFFFFFFFFFFFFF


def apksigner():
    found = shutil.which("apksigner")
    if found:
        return found
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        props = os.path.join(ROOT, "local.properties")
        if os.path.exists(props):
            for line in open(props):
                if line.startswith("sdk.dir="):
                    sdk = line.split("=", 1)[1].strip()
    tools = sorted(glob.glob(os.path.join(sdk or "", "build-tools", "*", "apksigner")))
    if not tools:
        sys.exit("apksigner not found: set ANDROID_HOME or put it on PATH")
    return tools[-1]


def signer_digest(signer, apk):
    out = subprocess.run([signer, "verify", "--print-certs", apk], capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"{apk}: signature does not verify\n{out.stderr}")
    for line in out.stdout.splitlines():
        if "certificate SHA-256 digest:" in line:
            return line.rsplit(":", 1)[1].strip()
    sys.exit(f"{apk}: no signer certificate")


def main():
    signer = apksigner()
    shutil.rmtree(REPO, ignore_errors=True)
    os.makedirs(os.path.join(REPO, "apk"))
    os.makedirs(os.path.join(REPO, "icon"))

    index = []
    for info_path in sorted(glob.glob(os.path.join(ROOT, "src", "*", "*", "build", "extension.json"))):
        module = os.path.dirname(os.path.dirname(info_path))
        apks = glob.glob(os.path.join(module, "build", "outputs", "apk", "release", "*.apk"))
        if len(apks) != 1:
            sys.exit(f"{module}: expected one release APK, found {len(apks)}")
        apk = apks[0]
        digest = signer_digest(signer, apk)
        if digest != FINGERPRINT:
            sys.exit(f"{apk}: signed by {digest}, not the extensions key")

        info = json.load(open(info_path))
        pkg = info["pkg"]
        apk_name = f"{pkg.removeprefix('app.yomikku.extension.')}-v{info['versionName']}.apk"
        apk_name = "yomikku-" + apk_name
        shutil.copy(apk, os.path.join(REPO, "apk", apk_name))
        shutil.copy(
            os.path.join(module, "res", "mipmap-xxxhdpi", "ic_launcher.png"),
            os.path.join(REPO, "icon", f"{pkg}.png"),
        )
        langs = {s["lang"] for s in info["sources"]}
        index.append({
            "name": info["name"],
            "pkg": pkg,
            "apk": apk_name,
            "lang": langs.pop() if len(langs) == 1 else "all",
            "code": info["versionCode"],
            "version": info["versionName"],
            "nsfw": info["nsfw"],
            "sources": [
                {
                    "name": s["name"],
                    "lang": s["lang"],
                    "id": source_id(s["name"], s["lang"], s["versionId"]),
                    "baseUrl": s["baseUrl"],
                }
                for s in info["sources"]
            ],
        })

    if not index:
        sys.exit("no release builds found: run ./gradlew assembleRelease first")
    index.sort(key=lambda e: e["pkg"])
    with open(os.path.join(REPO, "index.min.json"), "w") as f:
        json.dump(index, f, ensure_ascii=False, separators=(",", ":"))
    with open(os.path.join(REPO, "index.json"), "w") as f:
        json.dump(index, f, ensure_ascii=False, indent=2)
    with open(os.path.join(REPO, "repo.json"), "w") as f:
        json.dump({"meta": META}, f, indent=2)
    print(f"{len(index)} extensions in {REPO}")


if __name__ == "__main__":
    main()
