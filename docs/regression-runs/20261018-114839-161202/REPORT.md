# Regression: FAIL

Run: 20261018-114839-161202

| Check | Status | Evidence |
| --- | --- | --- |
| dependencies | FAIL |  |
| frontend-unit | NOT RUN |  |
| frontend-build | NOT RUN |  |
| backend-h2 | NOT RUN |  |
| database-prepare | NOT RUN |  |
| backend-mysql | NOT RUN |  |
| backend-package | NOT RUN |  |
| browser | NOT RUN |  |
| workbook | NOT RUN |  |
| backend-restart | NOT RUN |  |
| orchestration | FAIL | Dependency installation failed. |
| backend-h2 results | FAIL | {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0} |
| backend-mysql results | FAIL | {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0} |
| frontend-unit results | NOT RUN | [Errno 2] No such file or directory: 'D:\\agent_learning\\Agricultural treade\\docs\\regression-runs\\20261018-114839-161202\\frontend-unit.json' |
| browser results | NOT RUN | [Errno 2] No such file or directory: 'D:\\agent_learning\\Agricultural treade\\docs\\regression-runs\\20261018-114839-161202\\browser-results.json' |

Browser details: [HTML report](browser-report/index.html), [JSON results](browser-results.json).
Logs, database migrations, backend reports and failure traces are saved in this run directory.

Manual checks: Android device, trusted HTTPS certificate and physical printing are NOT VERIFIED.
Automated desktop/mobile viewport and print-preview tests do not replace those checks.

Test services use loopback ports 13317 / 8082 / 4182 and private test databases.
Existing demo services and demo database are not used by this runner.
