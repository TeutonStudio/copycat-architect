#!/usr/bin/env python3
"""Validate source resources and ROM program identity, without game startup."""
from pathlib import Path
import json, subprocess
root=Path(__file__).resolve().parents[1]
resources=root/'src/main/resources'
for p in resources.rglob('*.json'):
    json.loads(p.read_text())
assert (root/'lua/architect.lua').read_bytes()==(resources/'data/computercraft/lua/rom/programs/turtle/architect.lua').read_bytes()
a=json.loads((resources/'assets/copycat_architect/lang/en_us.json').read_text())
b=json.loads((resources/'assets/copycat_architect/lang/de_de.json').read_text())
assert a.keys()==b.keys(), 'Language key mismatch'
subprocess.run(['luac','-p',str(root/'lua/architect.lua')],check=True)
print('JSON, language keys and ROM Lua: OK')
