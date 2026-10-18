# 全量回归测试

在项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Regression.ps1
```

需要 JDK 21、Maven、Node（项目要求 22.12+）、Python 3、MySQL 8.0 二进制程序及已安装的 Google Chrome。默认 JDK/Maven/MySQL 路径与本机演示脚本一致，更换机器可传 `-JavaHome`、`-Maven`、`-MysqlBin`。依赖通过 `npm ci` 按锁文件安装。

入口自动运行前端单元测试及类型检查/生产构建、H2 后端测试、真实 MySQL 后端测试、后端打包、全部 Playwright 用例、Excel 内容独立校验与后端重启持久化检查。后端打包阶段跳过重复测试，因为同轮已分别在 H2 和 MySQL 执行全部后端用例；两个测试阶段都是必选项。

## 数据隔离

专用 MySQL 实例的数据目录为 `.local/regression/mysql-data`，只监听回环端口 13317；后端为 8082，浏览器前端为 4182。任一专用端口被占用即拒绝运行，不复用已有服务。运行锁避免同一工作区重复启动。

每轮仅在此专用实例重建固定名称的 `harvest_test`、`harvest_migration_test` 和 `harvest_regression` 库。后端集成、历史升级和浏览器写入各用一个库；不使用演示端口 13316 或演示库 `harvest`。测试实例 root 空密码仅用于回环本地测试。

脚本保存并恢复所用进程环境变量，结束时关闭本轮启动的后端/MySQL；Playwright 负责关闭专用前端。若强制终止脚本或机器崩溃，进程可能残留，下一次会因端口占用而停止。核对命令行和数据目录后再停止残留测试进程，不要关闭日常演示服务。

## 结果判定

每轮结果位于 `docs/regression-runs/<新加坡时间与随机编号>/`：

- `REPORT.md`、`summary.json`：阶段结果及自动化整体结论。
- `stages.json`、各阶段日志：执行状态及失败原因。
- `backend-h2/`、`backend-mysql/`：JUnit XML 和文本报告。
- `frontend-unit.json`、`browser-results.json`、`browser-report/index.html`：前端与浏览器结果。
- `browser-artifacts/`：失败截图和 trace；`evidence/`：运行截图、导出文件及独立内容核验。
- `restart-before.json`、`restart-after.json`：后端重启前后的记录证据。

必选阶段及测试全部通过、没有跳过或不稳定用例时才返回退出码 0。失败、环境缺失、结果文件缺失或必选测试未执行都返回非零，不计为通过。首个环境/构建阻断可能使依赖阶段无法执行，报告明确标记未运行。正常测试失败时尽量继续其他独立阶段，以收集更多结果。

测试重试次数为零，禁止只执行聚焦用例的 `.only`。失败修复后重新执行完整命令。报告不覆盖历史批次；已有验收目录也不会再被测试截图或导出文件覆盖。

人工检查单独记录，不纳入“自动化通过”的设备实测声明，见 [设备人工回归清单](regression-manual-checklist.md)。业务到用例映射见 [回归覆盖清单](regression-coverage.md)。

## 新增及维护用例

后端新增 `*Test.java`，前端单元测试新增 `src/**/*.test.ts`，浏览器或 HTTP 验收新增 `e2e/*.spec.ts`，统一入口自动发现。浏览器证据使用 `e2e/artifacts.ts` 的 `artifact()` 或 `evidenceDir`，避免写入固定历史目录。

新功能需补业务正常流程、权限和相关异常边界用例；缺陷需补复现用例。同步维护覆盖清单，并在改进后运行全量回归。需求变化经用户确认后，同步更新需求说明及断言。仓库持续约定见根目录 `AGENTS.md`。
