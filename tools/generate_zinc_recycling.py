#!/usr/bin/env python3
"""Generate additive Create melting recipes from the pinned Create/Copycats crafting data.

Usage: python3 tools/generate_zinc_recycling.py CREATE_JAR COPYCATS_JAR
Only zinc is recovered. The cheapest complete recipe path bounds the refund, so
conversion recipes and functional items cannot multiply zinc. Fractions use batches.
"""
from collections import defaultdict
from fractions import Fraction
from pathlib import Path
import json
import sys
import zipfile

root = Path(__file__).resolve().parents[1]
recipes = defaultdict(list)
tags = defaultdict(list)
for jar in sys.argv[1:]:
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if not name.endswith('.json') or not name.startswith('data/'):
                continue
            parts = name.split('/')
            if '/tags/item/' in name:
                key = parts[1] + ':' + name.split('/tags/item/')[1][:-5]
                tags[key].extend(json.loads(archive.read(name)).get('values', []))
            if '/recipe/' not in name:
                continue
            data = json.loads(archive.read(name))
            result = data.get('result')
            if not isinstance(result, dict) or not isinstance(result.get('id'), str):
                continue
            kind = data.get('type')
            if kind == 'minecraft:stonecutting':
                inputs = [data['ingredient']]
            elif kind == 'minecraft:crafting_shapeless':
                inputs = data['ingredients']
            elif kind == 'minecraft:crafting_shaped':
                inputs = [data['key'][char] for row in data['pattern'] for char in row if char != ' ']
            else:
                continue
            recipes[result['id']].append((inputs, result.get('count', 1)))

known = {'create:zinc_ingot': Fraction(256), 'create:zinc_nugget': Fraction(256, 9), 'create:zinc_block': Fraction(2304)}

def members(tag, seen=frozenset()):
    if tag in seen:
        return []
    result = []
    for entry in tags.get(tag, []):
        value = entry.get('id', '') if isinstance(entry, dict) else entry
        result += members(value[1:], seen | {tag}) if value.startswith('#') else [value]
    return result

def ingredient_cost(value, active):
    if isinstance(value, list):
        costs = [ingredient_cost(choice, active) for choice in value]
        return min((cost for cost in costs if cost is not None), default=None)
    if 'item' in value:
        return cost(value['item'], active)
    tag = value.get('tag', '')
    if tag == 'c:ingots/zinc':
        return Fraction(256)
    choices = members(tag)
    if not choices:
        return None if tag.startswith('copycats:') else Fraction(0)
    costs = [cost(item, active) for item in choices]
    return min((item for item in costs if item is not None), default=None)

derived = {}

def cost(item, active=frozenset()):
    if item in known:
        return known[item]
    # Discard the substrates of functional items rather than crediting any unproven zinc in them.
    return derived.get(item) if 'copycat' in item else Fraction(0)

for iteration in range(256):
    changed = False
    for item in sorted(item for item in recipes if 'copycat' in item):
        candidates = []
        for inputs, count in recipes[item]:
            values = [ingredient_cost(value, frozenset()) for value in inputs]
            if all(value is not None for value in values):
                candidates.append(sum(values, Fraction(0)) / count)
        value = min(candidates, default=None)
        if value is not None and (item not in derived or value < derived[item]):
            derived[item] = value
            changed = True
    if not changed:
        break
else:
    raise RuntimeError('Recycling costs did not converge; check upstream conversion recipes')

out = root / 'src/main/resources/data/copycat_architect/recipe/zinc_recycling'
out.mkdir(parents=True, exist_ok=True)
records = {}
for item in sorted(item for item in recipes if 'copycat' in item):
    value = cost(item)
    if value is None or value <= 0:
        continue
    batch = value.denominator
    assert batch <= 9, (item, value)
    data = {
        'type': 'create:mixing',
        'ingredients': [{'item': item}] * batch,
        'results': [{'id': 'copycat_architect:molten_zinc', 'amount': value.numerator}],
        'heat_requirement': 'heated',
    }
    filename = item.replace(':', '_') + '.json'
    (out / filename).write_text(json.dumps(data, indent=2) + '\n')
    records[item] = {'items': batch, 'units': value.numerator}
metadata = root / 'src/main/resources/data/copycat_architect/zinc_recycling.json'
metadata.write_text(json.dumps(records, indent=2) + '\n')
print(f'Generated {len(records)} additive Copycat recycling recipes; 256 mB per zinc ingot.')
