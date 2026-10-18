# 回归覆盖清单

本表将业务要求映射到自动化测试；是否实际通过以每轮 `REPORT.md` 为准。用例发现由测试框架自动完成，新增用例不依赖手工维护执行列表。

## 2026-10-18 流程调整新增及替代覆盖

以下覆盖按用户最新确认规则更新，取代下方旧版派工及独立确认补结算的界面要求；历史快照、内部历史差额和其他有效断言继续保留。

| 范围 | 正常、权限及异常边界 | 用例 |
| --- | --- | --- |
| 规则直接保存 | 修改单价后无需试算可发布；服务端缺字段、非法阶段、追溯降价及结构兼容校验继续生效 | `confirmed-changes.spec.ts` 规则/重量流程、既有 API-04/10 及后端计价用例 |
| 重量配置及历史版本 | 老板/记录员维护；记录员不能改账号；毛重和单筐皮重分别选择；容器数乘扣重；净重只读且在卡片下方；服务端拒绝篡改版本重量和负净重；旧版本晚传不受后来改配置影响 | `configuredWeightsUseImmutableRevisionsAndRejectTamperedValues`、`WeightField.test.ts`（斤/克六位小数及只读位置）、`confirmed-changes.spec.ts`、更新后的 `reference-improvements.spec.ts` |
| 单组成归属更正 | 多组多棚中只移动所选组成，可同时改组与地块，其他数量及总量不变；旧协议兼容；地块单独调整保留签字 | `movingOneCompositionCanChangeGroupAndPlotWithoutChangingOtherQuantity`、原 locationOnly/plot 用例、`confirmed-changes.spec.ts` |
| 直接付款及即时余额 | 无初次确认步骤；只确认当前工人；更正后立即可补付；旧付款ID重传不确认新账；快照不改写；减量形成负余额；付款重试、并发超付继续受保护 | `payAutomaticallySnapshotsOnlyCurrentWorkerAndCorrectionIsImmediatelyPayable`、原付款/并发用例、`confirmed-changes.spec.ts` 两种宽度实际操作及截图 |
| 查询分页与导出 | 服务端首/末页、总数、非法页码/页大小、鉴权；查询回首页；迟到响应不覆盖新查询；离线只查本机；工资导出不受当前页10条限制，实际Excel含全部25人；每日核对按小写工号查询22人时列表20条、生成快照及打印内容均22人 | `queriesPageFilterBoundariesPermissionsAndRemovedDispatch`、`DataList.test.ts`、`confirmed-changes.spec.ts` |
| 移除派工 | 页面入口不存在；原API返回410；不产生工资或安排；本机旧队列不再上传且数据保留 | `retiredDispatchRefusesNewWorkAndDoesNotGenerateWages`、query权限测试、`data.test.ts`、更新后的 `feature-expansion.spec.ts` |
| 离线页面重开 | 页面准备后断网录入、刷新及关闭重开，原UUID保留，联网只入账一次；API不进入静态缓存 | `ledger.spec.ts` 离线流程、NET-03；开发入口专项验证另记本轮报告 |
| V5升级 | V3历史记录/快照/付款/签字保留；旧筐型升级生成皮重版本，重复迁移无变更 | `MigrationIntegrationTest` |

| 范围 | 主要断言与场景 | 用例来源 |
| --- | --- | --- |
| 登录与账号权限 | 未登录、错误密码、角色修改、停用即时失效、自我保护、退出撤销令牌、离线退出和刷新 | `acceptance-api.spec.ts` API-01/03、`logout.spec.ts`、`data.test.ts` |
| 基础资料 | 工号唯一、停用阻止录入、类别/产品、班组与筐型页面维护 | API-02/04、`reference-improvements.spec.ts` |
| 六类资料导入 | 工人新增不覆盖、其余五类预览/新建/更新、缺失关联创建、预览不写业务数据、所属账号限制、重复确认、任一行失败及过期预览整批回滚、公式及损坏文件拒绝 | `LedgerIntegrationTest` 中 import/Import 用例及 `everyNonWorkerImportSupportsPreviewCreateUpdateAndOwnerBinding`、`feature-expansion.spec.ts` |
| 时段唯一记录 | 同业务键拒绝重复、上午下午区分、删除保留唯一键、多账号协作 | `businessKeyRejectsDuplicateButSeparatesPeriodsAndDays`、`deletionPreservesBusinessKeyAndRecorderCanRestore`、API-06/08 |
| 采摘录入与更正 | 连续录入、增加量/更正区分、跨记录员历史及已结算更正、原因必填、审计、分组历史归属 | grouped/plot 后端用例、`reference-improvements.spec.ts`、`history-regression.spec.ts` |
| 删除及恢复 | 本人未结算历史记录可删除、他人删除拒绝、老板与记录员均不能删除已结算记录、晚传记录补结算前后的保护变化、取消不提交、恢复 | 后端 deletion/settled/lateHistorical 用例、`deletion.spec.ts`（两种宽度×五种响应）、API-07 |
| 计费与金额 | 半筐、斤公斤克、扣皮服务端重算、分段与总重量优先分配、未分配量拒绝、逐笔到分、追溯涨价、降价及结构兼容性 | 后端 pricing/round/halfBaskets/configurable/month/retroactive/gross 用例、API-04/05/10 |
| 班组与地块成本 | 同一记录多组多棚、纯计件计重/混合/总重分配、分币尾差合计守恒、未指定地块、仅移动地块保留工资和签字 | 后端 plot/mixed/locationOnly/groupWages 用例、`feature-expansion.spec.ts` |
| 每日派工移除 | 入口不存在、API410、新业务拒绝、本机旧队列保留且不再发送 | `retiredDispatchRefusesNewWorkAndDoesNotGenerateWages`、查询权限用例、`data.test.ts`、`feature-expansion.spec.ts` |
| 离线与账号隔离 | 本机事务保存、离线刷新/关页重开、断网恢复自动补传、不同账号隔离、切换账号期间请求归属、中断提交恢复 | `data.test.ts`、`ledger.spec.ts`、`acceptance-network.spec.ts` NET-01/03 |
| 重试与并发 | 同 UUID 内容/账号绑定、响应丢失、503/超时、混合批次部分成功、版本冲突、旧刷新不覆盖新版本、重复新增唯一赢家 | 后端 operationRetry/optimistic/concurrent/directSubmission、API-06/08、NET-02/04、`data.test.ts` |
| 历史快照与付款确认 | 当前应发/未付自动更新、付款时确认单人工资、历史快照不改写、晚传/改价/更正差额；旧确认协议兼容及并发保护 | 后端 settlement/retroactive/concurrent 用例、API-09、`history-regression.spec.ts` |
| 付款 | 分次支付、ID 重试内容绑定、超付拒绝、并发支付不超付、已付款后减量形成负未付余额且保留历史 | `settlementSnapshotsLateRecordAndPartialPayment`、`paymentRetryBindsPayloadAndConcurrentPaymentsCannotOverpay`、`reducingAlreadyPaidWorkPreservesSnapshotsAndShowsOverpayment`、API-09 |
| 每日核对、签字及快照 | 数量/价格更改签字失效、结算不使签字失效、旧快照不可改写、打印后补录纳入对照、过期快照拒绝 | 后端 signature/printSnapshots/printTemplate 用例、`reference-improvements.spec.ts`、`feature-expansion.spec.ts` |
| Excel 导出 | 实际文件 ZIP 校验和及 OOXML 解析、两个工作表、目标工人数量/应发/已确认/已付/未付、结算明细 | API-09、`scripts/Verify-QAWorkbook.py` |
| 统计、导航与反馈 | 工资/重量/筐数切换、三个图表、无页面异常、权限入口、顶部提示自动消失、电脑1440/手机390宽度布局 | `ledger.spec.ts`、SYS-01、`entry-feedback.spec.ts`、`logout.spec.ts` |
| 新建及升级数据库 | H2/MySQL 从空库迁移、V3 带历史数据升至最新版本，数量组成/已删除记录/唯一键/结算快照/付款/签字保留，重复迁移无变更 | 全套后端启动、`MigrationIntegrationTest` |
| 构建及重启持久化 | Vue/TypeScript 检查、生产 PWA 打包、后端打包、重启后重新登录及原令牌读取，当前月记录逐值不变 | 统一入口 frontend-build/backend-package/backend-restart |

`deletion.spec.ts` 使用模拟 API 进行确定的界面异常分支验证；其余涉及真实写入的浏览器/API 用例经统一入口连接专用 MySQL 后端。打印预览/PDF 与手机宽度模拟的边界见人工清单。
