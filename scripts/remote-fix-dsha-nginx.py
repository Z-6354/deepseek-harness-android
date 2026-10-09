#!/usr/bin/env python3
import re
from pathlib import Path

p = Path("/etc/nginx/sites-enabled/dsh.wannian.fun")
text = p.read_text(encoding="utf-8")
new = """    # DSHA app update channel (static; bypasses Harness auth)
    location ^~ /dsha/ {
        root /var/www;
        default_type application/octet-stream;
        add_header Cache-Control "no-store";
        add_header X-Content-Type-Options nosniff;
    }

"""
text2, n = re.subn(
    r"    # DSHA app update channel \(static; bypasses Harness auth\)\n    location \^~ /dsha/ \{.*?\n    \}\n\n",
    new,
    text,
    count=1,
    flags=re.S,
)
if n != 1:
    raise SystemExit(f"replace failed n={n}")
p.write_text(text2, encoding="utf-8")
print("patched")
print(text2[text2.find("DSHA app update"): text2.find("DSHA app update") + 280])
