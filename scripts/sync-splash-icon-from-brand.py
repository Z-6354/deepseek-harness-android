#!/usr/bin/env python3
"""Generate a splash-only icon denser than the adaptive launcher foreground.

Android 12 splash places the animated icon in a centered circle. Adaptive
launcher artkeeps ~55% fill for mask safe-zone; splash can use a tighter crop
so the character reads larger.
"""

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

# Same canvas as adaptive foreground (108dp), higher content fill for splash.
DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
CONTENT = 0.88


def main() -> int:
    svg = SVG.read_text(encoding="utf-8")
    match = re.search(r'xlink:href="data:image/png;base64,([^"]+)"', svg)
    if not match:
        print("error: brand SVG has no embedded PNG", file=sys.stderr)
        return 1

    src = Image.open(io.BytesIO(base64.b64decode(match.group(1)))).convert("RGBA")
    print(f"source {src.size} content_scale={CONTENT}")

    for dens, canvas in DENSITIES.items():
        ddir = RES / f"drawable-{dens}"
        ddir.mkdir(parents=True, exist_ok=True)
        box = int(round(canvas * CONTENT))
        art = src.copy()
        art.thumbnail((box, box), Image.Resampling.LANCZOS)
        layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
        layer.alpha_composite(art, ((canvas - art.width) // 2, (canvas - art.height) // 2))
        out = ddir / "splash_icon.png"
        layer.save(out, optimize=True)
        print(f"wrote {out.relative_to(ROOT)} art={art.size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
