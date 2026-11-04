"""Builds the extension repo folder that Tachimanga reads.

Usage: python3 scripts/make_repo.py <output dir>

Collects release APKs from src/<lang>/<name>/build, copies them with their icons,
and writes index.min.json, index.json and repo.json.
"""
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = Path(sys.argv[1] if len(sys.argv) > 1 else ROOT / "repo").resolve()
LIB_VERSION = "1.4"
PKG_PREFIX = "ru.tachimanga.ext."

REPO_META = {
    "name": "Tachimanga Sources for Russians",
    "shortName": "ru-sources",
    "website": "https://github.com/Sw1tchtaks/tachimanga-sources-for-russians",
}


def source_id(name: str, lang: str, version_id: int) -> str:
    """Same formula as HttpSource.id in Tachiyomi."""
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()
    value = int.from_bytes(digest[:8], "big") & 0x7FFFFFFFFFFFFFFF
    return str(value)


def signing_fingerprint(apk: Path) -> str:
    """SHA-256 of the certificate the APK is signed with."""
    out = subprocess.run(["keytool", "-printcert", "-jarfile", str(apk)], capture_output=True, text=True, check=True).stdout
    for line in out.splitlines():
        line = line.strip()
        if line.startswith("SHA256:"):
            return line.split(":", 1)[1].strip().replace(":", "").lower()
    raise SystemExit("SHA256 fingerprint not found")


def main() -> None:
    if OUT.exists():
        shutil.rmtree(OUT)
    (OUT / "apk").mkdir(parents=True)
    (OUT / "icon").mkdir(parents=True)

    entries = []
    for ext_json in sorted((ROOT / "src").glob("*/*/ext.json")):
        ext_dir = ext_json.parent
        lang = ext_dir.parent.name
        meta = json.loads(ext_json.read_text(encoding="utf-8"))
        version = f"{LIB_VERSION}.{meta['versionCode']}"
        apk_name = f"tachiyomi-{lang}.{ext_dir.name}-v{version}.apk"
        built = ext_dir / "build" / "outputs" / "apk" / "release" / f"tachiyomi-{lang}.{ext_dir.name}-v{version}-release.apk"
        if not built.exists():
            raise SystemExit(f"Missing APK: {built}")
        pkg = PKG_PREFIX + ext_dir.name
        shutil.copy(built, OUT / "apk" / apk_name)
        shutil.copy(ext_dir / "res" / "mipmap-xxxhdpi" / "ic_launcher.png", OUT / "icon" / f"{pkg}.png")

        sources = []
        for src in meta["sources"]:
            sources.append({
                "name": src["name"],
                "lang": lang,
                "id": src.get("id") or source_id(src["name"], lang, src.get("versionId", 1)),
                "baseUrl": src["baseUrl"],
                "versionId": src.get("versionId", 1),
            })

        entries.append({
            "name": f"Tachiyomi: {meta['name']}",
            "pkg": pkg,
            "apk": apk_name,
            "lang": lang,
            "code": meta["versionCode"],
            "version": version,
            "nsfw": 1 if meta.get("nsfw") else 0,
            "sources": sources,
        })

    (OUT / "index.min.json").write_text(json.dumps(entries, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (OUT / "index.json").write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding="utf-8")
    first_apk = next((OUT / "apk").glob("*.apk"))
    repo = {"meta": {**REPO_META, "signingKeyFingerprint": signing_fingerprint(first_apk)}}
    (OUT / "repo.json").write_text(json.dumps(repo, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {len(entries)} extensions to {OUT}")


if __name__ == "__main__":
    main()
