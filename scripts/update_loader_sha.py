#!/usr/bin/env python3
from pathlib import Path
import hashlib
import re
import sys

root = Path(__file__).resolve().parents[1]
dex = root / "build" / "etg-max-bridge.dex"
plugin = root / "plugin" / "etg_max.py"

if not dex.exists():
    raise SystemExit("build/etg-max-bridge.dex does not exist")

sha = hashlib.sha256(dex.read_bytes()).hexdigest()
text = plugin.read_text()
text = re.sub(r'DEFAULT_DEX_SHA256 = ".*?"', f'DEFAULT_DEX_SHA256 = "{sha}"', text)
plugin.write_text(text)
print(sha)
