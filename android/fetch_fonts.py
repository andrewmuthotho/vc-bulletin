"""
Runs on GitHub during the app build, before the app is assembled.

  CC_FONTS=custom  -> download Hanken Grotesk + DM Mono from Google Fonts into
                      app/src/main/res/font/. If anything goes wrong, fall back
                      to system fonts automatically so the build never breaks.
  CC_FONTS=system  -> skip the download and use the phone's built-in fonts
                      (sans-serif-medium for headlines, monospace for data).

The fonts are fetched at build time so nobody has to upload font files by hand.
"""

import os
import re
import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).parent
RES = HERE / "app" / "src" / "main" / "res"
FONT_DIR = RES / "font"
LAYOUTS = RES / "layout"

# file name in res/font  ->  (Google Fonts family, weight)
WANTED = {
    "hanken_grotesk_medium": ("Hanken Grotesk", "500"),
    "hanken_grotesk_bold": ("Hanken Grotesk", "700"),
    "dm_mono_regular": ("DM Mono", "400"),
    "dm_mono_medium": ("DM Mono", "500"),
}

# What each custom font becomes if we fall back to system fonts.
FALLBACK = {
    '"@font/hanken_grotesk_medium"': '"sans-serif-medium"',
    '"@font/hanken_grotesk_bold"': '"sans-serif" android:textStyle="bold"',
    '"@font/dm_mono_regular"': '"monospace"',
    '"@font/dm_mono_medium"': '"monospace"',
}

CSS_URL = ("https://fonts.googleapis.com/css2?family=Hanken+Grotesk:wght@500;700"
           "&family=DM+Mono:wght@400;500")


def get(url):
    # A plain, non-browser user agent makes Google Fonts serve .ttf files,
    # which is the format Android needs (browsers get .woff2 instead).
    req = urllib.request.Request(url, headers={"User-Agent": "VCBulletinBuild/1.0"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read()


def download_fonts():
    css = get(CSS_URL).decode("utf-8")
    found = {}
    for block in css.split("@font-face")[1:]:
        family = re.search(r"font-family:\s*'([^']+)'", block)
        weight = re.search(r"font-weight:\s*(\d+)", block)
        url = re.search(r"url\((https://[^)]+)\)", block)
        rng = re.search(r"unicode-range:\s*([^;]+);", block)
        if not (family and weight and url):
            continue
        key = (family.group(1), weight.group(1))
        # If Google splits a font into language subsets, prefer the Latin one.
        is_latin = rng is None or rng.group(1).strip().startswith("U+0000")
        if key not in found or is_latin:
            found[key] = url.group(1)

    FONT_DIR.mkdir(parents=True, exist_ok=True)
    for name, key in WANTED.items():
        if key not in found:
            raise RuntimeError(f"Google Fonts didn't list {key}")
        data = get(found[key])
        if data[:4] not in (b"\x00\x01\x00\x00", b"true", b"OTTO"):
            raise RuntimeError(f"{name}: not a TTF/OTF file (got {data[:4]!r})")
        (FONT_DIR / f"{name}.ttf").write_bytes(data)
        print(f"  font ok: {name}.ttf ({len(data) // 1024} KB)")


def use_system_fonts(reason):
    print(f"Using system fonts: {reason}")
    if FONT_DIR.exists():
        for f in FONT_DIR.glob("*.ttf"):
            f.unlink()
    for layout in LAYOUTS.glob("*.xml"):
        text = layout.read_text(encoding="utf-8")
        for old, new in FALLBACK.items():
            text = text.replace(old, new)
        layout.write_text(text, encoding="utf-8")


def main():
    mode = os.environ.get("CC_FONTS", "custom").strip().lower()
    if mode == "system":
        use_system_fonts("CC_FONTS is set to system")
        return
    try:
        download_fonts()
        print("Using custom fonts: Hanken Grotesk + DM Mono")
    except Exception as e:  # never let fonts break the build
        use_system_fonts(f"download failed ({e})")


if __name__ == "__main__":
    sys.exit(main())
