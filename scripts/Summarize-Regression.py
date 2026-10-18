"""Fail closed on missing, skipped, focused, flaky or failed required checks."""
import json
import sys
from pathlib import Path
from xml.etree import ElementTree as ET

root = Path(sys.argv[1])
stages = json.loads((root / 'stages.json').read_text(encoding='utf-8-sig'))
if isinstance(stages, dict):
    stages = [stages]
checks = []
required = ['dependencies', 'frontend-unit', 'frontend-build', 'backend-h2', 'database-prepare',
            'backend-mysql', 'backend-package', 'browser', 'workbook', 'backend-restart']
for name in required:
    stage = next((s for s in stages if s['name'] == name), None)
    checks.append((name, stage['status'] if stage else 'NOT RUN', ''))
for stage in stages:
    if stage['name'] not in required:
        checks.append((stage['name'], stage['status'], stage.get('message', '')))

for name in ['backend-h2', 'backend-mysql']:
    reports = list((root / name).glob('TEST-*.xml'))
    totals = {key: 0 for key in ['tests', 'failures', 'errors', 'skipped']}
    for report in reports:
        suite = ET.parse(report).getroot()
        for key in totals:
            totals[key] += int(suite.attrib.get(key, 0))
    ok = bool(reports) and totals['tests'] > 0 and not any(totals[k] for k in ['failures', 'errors', 'skipped'])
    checks.append((name + ' results', 'PASS' if ok else 'FAIL', str(totals)))

try:
    unit = json.loads((root / 'frontend-unit.json').read_text(encoding='utf-8-sig'))
    ok = unit['success'] and unit['numTotalTests'] > 0 and unit['numPassedTests'] == unit['numTotalTests']
    checks.append(('frontend-unit results', 'PASS' if ok else 'FAIL', f"{unit['numPassedTests']}/{unit['numTotalTests']} passed"))
except (OSError, ValueError, KeyError) as error:
    checks.append(('frontend-unit results', 'NOT RUN', str(error)))
try:
    browser = json.loads((root / 'browser-results.json').read_text(encoding='utf-8-sig'))
    stats = browser['stats']
    ok = stats['expected'] > 0 and not any(stats[k] for k in ['unexpected', 'flaky', 'skipped']) and not browser.get('errors')
    checks.append(('browser results', 'PASS' if ok else 'FAIL', str(stats)))
except (OSError, ValueError, KeyError) as error:
    checks.append(('browser results', 'NOT RUN', str(error)))

passed = all(status == 'PASS' for _, status, _ in checks)
summary = {'status': 'PASS' if passed else 'FAIL', 'checks': [dict(name=n, status=s, detail=d) for n, s, d in checks],
           'manual': {'android': 'NOT VERIFIED', 'device_certificate_trust': 'NOT VERIFIED', 'physical_printing': 'NOT VERIFIED'}}
(root / 'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
lines = [f"# Regression: {summary['status']}", '', f'Run: {root.name}', '',
         '| Check | Status | Evidence |', '| --- | --- | --- |']
for name, status, detail in checks:
    lines.append(f"| {name} | {status} | {detail.replace('|', '/')} |")
lines += ['', 'Browser details: [HTML report](browser-report/index.html), [JSON results](browser-results.json).',
          'Logs, database migrations, backend reports and failure traces are saved in this run directory.', '',
          'Manual checks: Android device, trusted HTTPS certificate and physical printing are NOT VERIFIED.',
          'Automated desktop/mobile viewport and print-preview tests do not replace those checks.', '',
          'Test services use loopback ports 13317 / 8082 / 4182 and private test databases.',
          'Existing demo services and demo database are not used by this runner.']
(root / 'REPORT.md').write_text('\n'.join(lines) + '\n', encoding='utf-8')
print(f"Regression {summary['status']}: {root}")
sys.exit(0 if passed else 1)
