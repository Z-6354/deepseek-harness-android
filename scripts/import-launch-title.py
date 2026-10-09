#!/usr/bin/env python3
"""Import DSHA title art into density drawables (black keyed for white splash)."""

from __future__ import annotations

import base64
import io
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SRC = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "brand" / "dsha-title.svg"
RES = ROOT / "app" / "src" / "main" / "res"
DENSITIES = (("mdpi", 240), ("hdpi", 360), ("xhdpi", 480), ("xxhdpi", 720), ("xxxhdpi", 960))


def load_image(path: Path) -> Image.Image:
    if path.suffix.lower() == ".svg":
        text = path.read_text(encoding="utf-8")
        match = re.search(r'xlink:href="data:image/png;base64,([^"]+)"', text)
        if not match:
            raise SystemExit(f"error: {path} has no embedded PNG")
        return Image.open(io.BytesIO(base64.b64decode(match.group(1)))).convert("RGBA")
    return Image.open(path).convert("RGBA")


def main() -> int:
    if not SRC.is_file():
        print("usage: import-launch-title.py [title.png|title.svg]", file=sys.stderr)
        return 1

    im = load_image(SRC)
    arr = np.array(im).astype(np.float32)
    lum = arr[:, :, :3].mean(axis=2)
    fade = np.clip((lum - 10.0) / 28.0, 0.0, 1.0)
    arr[:, :, 3] = arr[:, :, 3] * fade
    out = arr.astype(np.uint8)

    alpha = out[:, :, 3]
    ys, xs = np.where(alpha > 8)
    if len(xs):
        pad = 8
        y0, y1 = max(0, ys.min() - pad), min(out.shape[0], ys.max() + pad + 1)
        x0, x1 = max(0, xs.min() - pad), min(out.shape[1], xs.max() + pad + 1)
        out = out[y0:y1, x0:x1]

    img = Image.fromarray(out, "RGBA")
    print(f"cropped {img.size}")

    brand = ROOT / "brand" / "dsha-title.png"
    img.save(brand, optimize=True)
    print(f"wrote {brand.relative_to(ROOT)}")

    for dens, width in DENSITIES:
        ddir = RES / f"drawable-{dens}"
        ddir.mkdir(parents=True, exist_ok=True)
        scaled = img.copy()
        scaled.thumbnail((width, width), Image.Resampling.LANCZOS)
        path = ddir / "launch_title.png"
        scaled.save(path, optimize=True)
        print(f"wrote {path.relative_to(ROOT)} {scaled.size}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
