# Regression: FAIL

Run: 20261018-114922-552cd3

| Check | Status | Evidence |
| --- | --- | --- |
| dependencies | PASS |  |
| frontend-unit | PASS |  |
| frontend-build | PASS |  |
| backend-h2 | PASS |  |
| database-prepare | PASS |  |
| backend-mysql | PASS |  |
| backend-package | FAIL |  |
| browser | NOT RUN |  |
| workbook | NOT RUN |  |
| backend-restart | NOT RUN |  |
| orchestration | FAIL | Build failed; browser testing cannot proceed. |
| backend-h2 results | PASS | {'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0} |
| backend-mysql results | PASS | {'tests': 51, 'failures': 0, 'errors': 0, 'skipped': 0} |
| frontend-unit results | PASS | 19/19 passed |
| browser results | NOT RUN | [Errno 2] No such file or directory: 'D:\\agent_learning\\Agricultural treade\\docs\\regression-runs\\20261018-114922-552cd3\\browser-results.json' |

Browser details: [HTML report](browser-report/index.html), [JSON results](browser-results.json).
Logs, database migrations, backend reports and failure traces are saved in this run directory.

Manual checks: Android device, trusted HTTPS certificate and physical printing are NOT VERIFIED.
Automated desktop/mobile viewport and print-preview tests do not replace those checks.

Test services use loopback ports 13317 / 8082 / 4182 and private test databases.
Existing demo services and demo database are not used by this runner.
