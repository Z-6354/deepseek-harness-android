#!/usr/bin/env python3
"""Regenerate adaptive launcher foreground from brand/deepseek-mobile.svg."""

from __future__ import annotations

import base64
import io
import re
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "brand" / "deepseek-mobile.svg"
RES = ROOT / "app" / "src" / "main" / "res"

# Adaptive icon foreground canvas is 108dp. Content scale leaves margin so
# important artwork stays inside the system mask safe zone.
DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
# Keep artwork well inside the adaptive-icon mask (~66% safe zone).
CONTENT = 0.55


def main() -> int:
    svg = SVG.read_text(encoding="utf-8")
    match = re.search(r'xlink:href="data:image/png;base64,([^"]+)"', svg)
    if not match:
        print("error: brand SVG has no embedded PNG", file=sys.stderr)
        return 1

    src = Image.open(io.BytesIO(base64.b64decode(match.group(1)))).convert("RGBA")
    print(f"source {src.size} {src.mode}")

    old_vector = RES / "drawable" / "ic_launcher_foreground.xml"
    if old_vector.exists():
        old_vector.unlink()
        print(f"removed {old_vector.relative_to(ROOT)}")

    for dens, canvas in DENSITIES.items():
        ddir = RES / f"drawable-{dens}"
        ddir.mkdir(parents=True, exist_ok=True)
        box = int(round(canvas * CONTENT))
        art = src.copy()
        art.thumbnail((box, box), Image.Resampling.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(art, ((canvas - art.width) // 2, (canvas - art.height) // 2))
        out = ddir / "ic_launcher_foreground.png"
        layer.save(out, optimize=True)
        print(f"wrote {out.relative_to(ROOT)} art={art.size}")

    preview = ROOT / "brand" / "deepseek-mobile.png"
    src.save(preview, optimize=True)
    print(f"wrote {preview.relative_to(ROOT)} ({preview.stat().st_size} bytes)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
