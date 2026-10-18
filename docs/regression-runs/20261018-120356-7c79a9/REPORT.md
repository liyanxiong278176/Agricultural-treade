# Regression: FAIL

Run: 20261018-120356-7c79a9

| Check | Status | Evidence |
| --- | --- | --- |
| dependencies | PASS |  |
| frontend-unit | PASS |  |
| frontend-build | PASS |  |
| backend-h2 | PASS |  |
| database-prepare | PASS |  |
| backend-mysql | PASS |  |
| backend-package | PASS |  |
| browser | FAIL |  |
| workbook | PASS |  |
| backend-restart | PASS |  |
| backend-h2 results | PASS | {'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0} |
| backend-mysql results | PASS | {'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0} |
| frontend-unit results | PASS | 21/21 passed |
| browser results | FAIL | {'startTime': '2026-10-18T04:05:27.928Z', 'duration': 132142.91499999998, 'expected': 44, 'skipped': 0, 'unexpected': 2, 'flaky': 0} |

Browser details: [HTML report](browser-report/index.html), [JSON results](browser-results.json).
Logs, database migrations, backend reports and failure traces are saved in this run directory.

Manual checks: Android device, trusted HTTPS certificate and physical printing are NOT VERIFIED.
Automated desktop/mobile viewport and print-preview tests do not replace those checks.

Test services use loopback ports 13317 / 8082 / 4182 and private test databases.
Existing demo services and demo database are not used by this runner.
