# Regression: PASS

Run: 20261018-122013-ef63ba

| Check | Status | Evidence |
| --- | --- | --- |
| dependencies | PASS |  |
| frontend-unit | PASS |  |
| frontend-build | PASS |  |
| backend-h2 | PASS |  |
| database-prepare | PASS |  |
| backend-mysql | PASS |  |
| backend-package | PASS |  |
| browser | PASS |  |
| workbook | PASS |  |
| backend-restart | PASS |  |
| backend-h2 results | PASS | {'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0} |
| backend-mysql results | PASS | {'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0} |
| frontend-unit results | PASS | 23/23 passed |
| browser results | PASS | {'startTime': '2026-10-18T04:22:22.981Z', 'duration': 169912.438, 'expected': 46, 'skipped': 0, 'unexpected': 0, 'flaky': 0} |

Browser details: [HTML report](browser-report/index.html), [JSON results](browser-results.json).
Logs, database migrations, backend reports and failure traces are saved in this run directory.

Manual checks: Android device, trusted HTTPS certificate and physical printing are NOT VERIFIED.
Automated desktop/mobile viewport and print-preview tests do not replace those checks.

Test services use loopback ports 13317 / 8082 / 4182 and private test databases.
Existing demo services and demo database are not used by this runner.
