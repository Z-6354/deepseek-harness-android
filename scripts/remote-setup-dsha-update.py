#!/usr/bin/env python3
"""Patch dsh.wannian.fun nginx to serve /dsha/ static update channel."""
from pathlib import Path
import shutil
import time

CONF = Path("/etc/nginx/sites-enabled/dsh.wannian.fun")
text = CONF.read_text(encoding="utf-8")
if "location ^~ /dsha/" in text:
    print("nginx already has /dsha/")
else:
    backup = CONF.with_suffix(CONF.suffix + f".bak.pre-dsha-{time.strftime('%Y%m%d-%H%M%S')}")
    shutil.copy2(CONF, backup)
    needle = "    location / {\n        proxy_pass http://dsh_web;"
    block = """    # DSHA app update channel (static; bypasses Harness auth)
    location ^~ /dsha/ {
        alias /home/ubuntu/.dsh/public/dsha/;
        default_type application/octet-stream;
        types { application/json json; application/vnd.android.package-archive apk; }
        add_header Cache-Control "no-store";
        add_header X-Content-Type-Options nosniff;
    }

"""
    idx = text.rfind(needle)
    if idx < 0:
        raise SystemExit("nginx location / not found")
    CONF.write_text(text[:idx] + block + text[idx:], encoding="utf-8")
    print(f"nginx patched; backup={backup}")
