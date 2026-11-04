"""Copies source icons into Android mipmap folders. Usage: python3 scripts/icons.py <icon.png> <ext dir>"""
import sys
from pathlib import Path
from PIL import Image

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

src, ext_dir = Path(sys.argv[1]), Path(sys.argv[2])
img = Image.open(src).convert("RGBA")
for density, size in SIZES.items():
    out = ext_dir / "res" / f"mipmap-{density}" / "ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.resize((size, size), Image.LANCZOS).save(out)
