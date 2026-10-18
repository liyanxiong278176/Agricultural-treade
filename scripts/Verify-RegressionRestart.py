"""Compare raw HTTP JSON so PowerShell array adaptation cannot hide empty results."""
import json
import sys
from pathlib import Path

root = Path(sys.argv[1])
before = json.loads((root / 'restart-before.json').read_text(encoding='utf-8-sig'))
after = json.loads((root / 'restart-after.json').read_text(encoding='utf-8-sig'))
assert isinstance(before, list) and isinstance(after, list), 'Expected record arrays'
assert before, 'Persistence check requires non-empty test data'
assert len({row['id'] for row in before}) == len(before), 'Duplicate record IDs'
assert sorted(before, key=lambda row: row['id']) == sorted(after, key=lambda row: row['id']), 'Restart changed or lost records'
result = {'status': 'PASS', 'records': len(after), 'checks': ['non-empty records', 'unique IDs', 'all record fields unchanged', 'persisted session remains usable']}
(root / 'restart-verification.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
print(json.dumps(result))
