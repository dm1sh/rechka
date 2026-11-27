#!/usr/bin/env python3
"""Fail CI if the model pins, onboarding and release packaging diverge."""

import argparse
import hashlib
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
MODEL = ROOT / "app/src/main/java/ru/dm1sh/rechka/model/GigaAmModel.kt"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--full-apk", type=Path)
    parser.add_argument("--light-apk", type=Path)
    args = parser.parse_args()
    source = MODEL.read_text(encoding="utf-8")
    constants = dict(re.findall(r'const val (ENCODER|DECODER|JOINER|TOKENS) = "([^"]+)"', source))
    entries = re.findall(r'ModelFile\((ENCODER|DECODER|JOINER|TOKENS),\s*([0-9_]+)L,\s*"([0-9a-f]{64})"\)', source)
    if len(constants) != 4 or len(entries) != 4:
        raise ValueError("Unable to find all four Android manifest entries")
    android = {constants[key]: (int(size.replace("_", "")), digest) for key, size, digest in entries}
    if len(android) != 4:
        raise ValueError("Android model filenames are not unique")

    gradle_source = (ROOT / "app/build.gradle.kts").read_text(encoding="utf-8")
    gradle = {name: (int(size.replace("_", "")), digest) for name, size, digest in re.findall(
        r'"(gigaam_v4_e2e_rnnt_[^"]+)"\s+to\s+\(([0-9_]+)L\s+to\s+"([0-9a-f]{64})"\)', gradle_source
    )}
    downloader_source = (ROOT / "scripts/download-model.sh").read_text(encoding="utf-8")
    downloader = dict(re.findall(r'\[(gigaam_v4_e2e_rnnt_[^\]]+)\]="([0-9a-f]{64})"', downloader_source))
    if gradle != android:
        raise ValueError("Gradle and Android model names/sizes/hashes do not match")
    if downloader != {name: digest for name, (_, digest) in android.items()}:
        raise ValueError("Downloader and Android model names/hashes do not match")

    resources = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
    onboarding = next(item.text or "" for item in resources.findall("string") if item.get("name") == "onb_model_files")
    workflow = (ROOT / ".github/workflows/build-release-apk.yml").read_text(encoding="utf-8")
    for name in android:
        if name not in onboarding:
            raise ValueError(f"{name} is missing from onboarding")
    if "scripts/check-model-manifest.py" not in workflow or "--full-apk" not in workflow or "--light-apk" not in workflow:
        raise ValueError("Release workflow does not verify both APKs using the pinned manifest")
    print(f"PASS: {len(android)} model entries agree across installer, Gradle, downloader, UI and release CI")

    if args.full_apk:
        with ZipFile(args.full_apk) as apk:
            for name, (size, expected) in android.items():
                entry = "assets/" + name
                try:
                    info = apk.getinfo(entry)
                except KeyError as error:
                    raise ValueError(f"{args.full_apk} is missing {entry}") from error
                digest = hashlib.sha256()
                with apk.open(info) as stream:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        digest.update(chunk)
                if info.file_size != size or digest.hexdigest() != expected:
                    raise ValueError(f"{args.full_apk} contains invalid {entry}")
            if any(name.startswith("assets/gigaam_v3_") for name in apk.namelist()):
                raise ValueError("Full APK unexpectedly contains legacy v3 model assets")
        print(f"PASS: full APK contains all four exact v4 files: {args.full_apk}")

    if args.light_apk:
        with ZipFile(args.light_apk) as apk:
            unexpected = [name for name in apk.namelist() if name.startswith("assets/gigaam_v")]
            if unexpected:
                raise ValueError(f"Light APK unexpectedly contains model assets: {unexpected}")
        print(f"PASS: light APK contains no GigaAM model assets: {args.light_apk}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, StopIteration) as error:
        print(f"Model manifest check failed: {error}", file=sys.stderr)
        sys.exit(1)
