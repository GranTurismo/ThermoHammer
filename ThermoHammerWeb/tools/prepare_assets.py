#!/usr/bin/env python3
"""Turns assets-src/ into what the site serves from static/: subset web fonts, icons and screenshots.

Run it once after changing anything in assets-src/ (the results are checked in, so the server never needs it):

    .venv/bin/python tools/prepare_assets.py
"""
from __future__ import annotations

import io
from pathlib import Path

from fontTools import subset
from fontTools.ttLib import TTFont
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "assets-src"
STATIC = ROOT / "static"

# Basic Latin, Latin-1, Latin Extended-A, plus the punctuation, arrows and maths the pages use.
UNICODES = (
    list(range(0x20, 0x7F)) + list(range(0xA0, 0x180))
    + [0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2026, 0x2032, 0x2033,
       0x2190, 0x2191, 0x2192, 0x2193, 0x2197, 0x21C4, 0x2212, 0x2248, 0x2260, 0x2264, 0x2265, 0x00D7, 0x00B7,
       0x25B2, 0x25B6, 0x25BC, 0x25CF, 0x2713, 0x2715, 0x20AC, 0x2122]
)

FONTS = {
    "spacegrotesk_500.ttf": "space-grotesk-500",
    "spacegrotesk_700.ttf": "space-grotesk-700",
    "jetbrainsmono_regular.ttf": "jetbrains-mono-400",
    "jetbrainsmono_medium.ttf": "jetbrains-mono-500",
    "jetbrainsmono_bold.ttf": "jetbrains-mono-700",
    "jetbrainsmono_extrabold.ttf": "jetbrains-mono-800",
}


def fonts() -> None:
    out = STATIC / "fonts"
    out.mkdir(parents=True, exist_ok=True)
    for src, name in FONTS.items():
        options = subset.Options()
        options.flavor = "woff2"
        options.layout_features = ["kern", "liga", "calt", "tnum", "zero", "ss01", "case", "frac"]
        options.ignore_missing_unicodes = True
        options.ignore_missing_glyphs = True
        options.notdef_outline = True
        options.name_IDs = [1, 2, 4, 6]
        options.drop_tables += ["DSIG"]
        font = TTFont(SRC / "fonts" / src)
        sub = subset.Subsetter(options)
        sub.populate(unicodes=UNICODES)
        sub.subset(font)
        font.flavor = "woff2"
        buf = io.BytesIO()
        font.save(buf)
        (out / f"{name}.woff2").write_bytes(buf.getvalue())
        print(f"font  {name}.woff2  {len(buf.getvalue()) / 1024:.1f} KB")


def icons() -> None:
    out = STATIC / "img"
    out.mkdir(parents=True, exist_ok=True)
    icon = Image.open(SRC / "icon_1024.png").convert("RGBA")
    for size, name in [(512, "icon-512.png"), (192, "icon-192.png"), (180, "apple-touch-icon.png"), (48, "favicon-48.png"),
                       (32, "favicon-32.png")]:
        icon.resize((size, size), Image.LANCZOS).save(out / name, optimize=True)
    icon.resize((48, 48), Image.LANCZOS).save(out / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
    # the mark in the header and the footer: the icon without its dark margin (the hammer on its plate)
    plate = icon.crop((100, 100, 924, 924))
    for size in (64, 128, 256):
        plate.resize((size, size), Image.LANCZOS).save(out / f"mark-{size}.webp", quality=90, method=6)
    plate.resize((512, 512), Image.LANCZOS).save(out / "mark-512.webp", quality=88, method=6)
    print("icons ok")


def screens() -> None:
    out = STATIC / "img" / "screens"
    out.mkdir(parents=True, exist_ok=True)
    for src in sorted((SRC / "screens").glob("*.png")):
        img = Image.open(src).convert("RGB")
        widths = (540, 1080) if img.width <= 1100 else (760, 1506)
        for w in widths:
            if w > img.width:
                w = img.width
            h = round(img.height * w / img.width)
            scaled = img.resize((w, h), Image.LANCZOS)
            target = out / f"{src.stem}-{w}.webp"
            scaled.save(target, quality=82, method=6)
            print(f"shot  {target.name}  {w}x{h}  {target.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    fonts()
    icons()
    screens()
