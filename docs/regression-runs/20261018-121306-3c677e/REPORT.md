# Regression: FAIL

Run: 20261018-121306-3c677e

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
| frontend-unit results | PASS | 23/23 passed |
| browser results | FAIL | {'startTime': '2026-10-18T04:15:24.167Z', 'duration': 180500.93300000002, 'expected': 45, 'skipped': 0, 'unexpected': 1, 'flaky': 0} |

Browser details: [HTML report](browser-report/index.html), [JSON results](browser-results.json).
Logs, database migrations, backend reports and failure traces are saved in this run directory.

Manual checks: Android device, trusted HTTPS certificate and physical printing are NOT VERIFIED.
Automated desktop/mobile viewport and print-preview tests do not replace those checks.

Test services use loopback ports 13317 / 8082 / 4182 and private test databases.
Existing demo services and demo database are not used by this runner.
