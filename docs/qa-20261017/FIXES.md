# 规则校验与 SQL 月度汇总修复

日期：2026-10-17（Asia/Singapore）。仅处理用户指定的 BUG-01 和 GAP-02。

## 修复内容

### 缺少规则字段时返回 400

`Pricing.validate` 分别检查规则对象、计费模式、阶段集合、阶段对象和阶段类型，再检查单价等字段。缺少 `mode`、阶段为 null、缺少阶段 `type` 等输入会抛出业务校验错误，由原有异常处理器返回 HTTP 400 和明确中文提示，不再触发 NullPointerException/500。

校验同时用于 `/api/rules/trial` 试算与 `/api/rules` 发布。新增后端回归测试覆盖 9 种不完整规则，分别调用两个接口，共 18 个异常输入请求，并断言未新增规则和审计记录。

### SQL GROUP BY + SUM 按月按工人汇总

`Payroll.preview` 保留现有规则计价器，对月份内每条记录使用适用日期的当前规则计价，统一重量单位并逐条舍入到分。每条记录的筐数、公斤数、金额和已确认金额通过绑定参数组成 SQL 派生表，关联真实 `harvest_record` 和 `worker` 表，在 MySQL 中按工人执行：

```sql
SELECT r.worker_id, w.name, w.code,
       SUM(q.pieces) AS pieces,
       SUM(q.weight_kg) AS weight_kg,
       SUM(q.earned) AS earned,
       SUM(q.settled) AS settled
FROM harvest_record r
JOIN (<参数化的逐条计价结果>) q ON q.record_id = r.id
JOIN worker w ON w.id = r.worker_id
WHERE r.work_date >= ? AND r.work_date < ?
GROUP BY r.worker_id, w.name, w.code
ORDER BY w.code, r.worker_id
```

按工人的工作量与工资总计不再使用 Java Map 累加。Java 仍负责每条记录内部的多阶段分配、重量换算、舍入，以及汇总后的差额相减；这些计算不等同于按工人聚合。计价结果以请求内派生表提供，不新增持久化工资缓存，追溯涨价后无需同步维护另一份余额。

删除记录保留账本/审计，但工作量和当前金额为零；已确认快照不改写。补结算仍按逐条差额计算，付款与导出 API 字段不变。无需数据库结构迁移。

## 验证范围

新增测试检查同一个工人的多个产品、上午下午、多工人、跨月份、逻辑删除、20 斤转 10 公斤、多个结算快照及付款、追溯涨价后待补差额、组合计费、逐条四舍五入、空月份。

有意核对的例子：

- 7 筐 × 3 元 + 10 公斤 × 0.5 元 = 26 元。
- 更正为 8 筐后合计 29 元；确认补结算并付 10 元后未付 19 元。
- 追溯涨至 4 元/筐后当前应发 37 元，已确认仍为 29 元，待补 8 元。
- 52 公斤依序分为 5 筐和余量 2 公斤，再加两条 1500 克称重记录；总量 5 筐/5 公斤，逐条舍入后工资 16.76 元。

## 结果与证据

- H2 后端完整回归：22/22 通过。
- MySQL 8.0.30 后端完整回归：22/22 通过。
- 最终接口与浏览器回归：34/34 通过，0 失败、0 跳过；包含规则试算与发布的真实 HTTP 400 验证、工资页面与导出、断网恢复、幂等重试及移动端交互。
- Excel 独立 OOXML 检查通过：验收工人 7 筐、当前/已确认工资 21 元、已付 10 元、未付 11 元。
- 新版本 JAR 打包成功。首次打包因旧 QA 进程占用文件导致重命名失败；核验所属项目和 8082 端口后，仅停止该 QA 进程，重新打包成功，再以新 JAR 启动同一独立 QA 数据库。8080 的现有服务没有被停止。

联调启动后的第一轮出现 1 项健康检查失败，原因是预览页先于后端就绪；其余 33 项通过。补充健康接口就绪条件后，完整重跑 34 项全部通过。该轮启动竞态证据保存在 `fix-startup-race-results.json`、`fix-startup-race.log`；没有把重跑前失败改记为通过。

浏览器和 Excel 最终复测结果由下列产物记录：

- [新版本浏览器报告](fix-browser-report/index.html)
- [新版本机器可读结果](fix-browser-results.json)
- [修复后的规则校验响应](invalid-rule-responses.json)
- [Excel 内容核对](workbook-verification.json)
- [H2 回归日志](fix-backend-h2.log)、[MySQL 回归及首次打包日志](fix-backend-mysql.log)、[成功打包日志](fix-package.log)、[最终浏览器日志](fix-final-e2e.log)

当前源代码和 `backend/target/harvest-ledger-1.0.0.jar` 已更新，8082 的独立 QA 实例使用新包。8080 的既有实例若仍在运行旧版本，需要通过原启动方式重启后加载修复。

复跑浏览器验收时在 `frontend` 设置 `QA_RUN_PREFIX=fix-`，执行 `npx.cmd playwright test --config playwright.acceptance.config.ts`；默认代理 8082，可通过 `QA_BACKEND_PORT` 指定另一独立 QA 端口。启动就绪检查改为同源代理后的 `/api/health`，确保真实后端已就绪后开始测试。
