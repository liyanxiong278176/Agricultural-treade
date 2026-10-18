# 田间账本 · 农场采摘计件计重管理系统

2026-10-18 操作调整：规则可直接保存；每日派工已移除；增加老板/记录员共用的重量配置；归属更正可修改班组及地块；工资更正后自动更新未付，直接登记付款并保留历史快照；数据列表查询分页、打印导出全部匹配数据。现行操作与边界见 [操作流程调整](docs/confirmed-changes-20261018.md)，实际结果见 [本轮验证](docs/changes-verification-20261018.md)。下方早期版本介绍中冲突的派工及单独结算流程以此为准。

Spring Boot + Vue 3 + MySQL，面向单基地中小农场的本地演示项目。支持电脑浏览器和安卓手机，包含基础资料、采摘记录、离线同步、工资结算与付款、统计看板及审计日志。

2026-10-17 素材改进版增加：班组管理、增加/更正数量留痕、人工称重扣皮、连续录入、每日核对打印及纸表签字状态、打印模板设置。保留原时段唯一记录，不使用二维码。具体操作见[改进版使用说明](docs/improvements-20261017.md)。

2026-10-17 功能扩展：新增每日派工（含离线修改）、地块/大棚人工成本、基础资料 Excel 导入、核对表打印快照与差异对照。记录员现可更正本人及他人的历史和已结算采摘记录，填写原因并留痕；结算与付款仍由老板负责。见[扩展使用说明](docs/expansion-guide-20261017.md)。

## 在这台电脑启动

可以双击根目录的 `启动演示.cmd`，或在项目根目录打开 PowerShell：

```powershell
.\scripts\Start-Demo.ps1
```

打开 [http://localhost:4173](http://localhost:4173)。如果脚本执行策略限制本次运行：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\Start-Demo.ps1
```

| 角色 | 账号 | 初始密码 |
| --- | --- | --- |
| 老板 | admin | Farm@2026 |
| 记录员甲 | recorder | Farm@2026 |
| 记录员乙 | recorder2 | Farm@2026 |

首次空库启动会生成 6 名工人、2 个类别、4 个产品、产品规则与本月示例采摘记录。数据持久保存在 `.local/mysql-data`，重启不会重置。

```powershell
# 修改源码后重新构建，先停止正在运行的版本
.\scripts\Stop-Demo.ps1
.\scripts\Start-Demo.ps1 -Build

# 停止本项目启动的服务，保留数据库
.\scripts\Stop-Demo.ps1
```

启动脚本使用本机已有的 JDK 21、Maven、Node 与 MySQL 程序。默认路径适配当前电脑；换电脑可传 `-JavaHome`、`-Maven`、`-MysqlBin`。需要 Node 22.12+、JDK 21、Maven 3.9+、MySQL 8.0+。本机默认 Java 8 不用于本项目。

| 服务 | 地址/位置 |
| --- | --- |
| 页面与同源 API 代理 | 4173 |
| Spring Boot | 8080 |
| 独立演示 MySQL | 仅 `127.0.0.1:13316`，数据库 `harvest` |
| 进程与日志 | `.local/*.pid`、`.local/*.log` |

独立数据库以本机 MySQL 二进制程序运行，不修改用户已有的 MySQL 服务或数据库。这个工作区演示实例的 root 无密码、只监听回环地址；不用于公网。若使用自己的数据库，后端支持 `DB_URL`、`DB_USER`、`DB_PASSWORD` 环境变量，且不能让测试库指向业务库。

## 安卓手机与真正离线重开

电脑通过 `localhost` 可直接验证页面离线缓存。安卓通过局域网 IP 访问时，完整的 Service Worker 离线能力需要**受信任的 HTTPS**，不是普通 HTTP 页面。

1. 电脑和手机连接同一 Wi-Fi，查看电脑 WLAN IPv4 地址。
2. 生成本项目演示证书，替换下面的 IP 为自己的地址：

   ```powershell
   .\scripts\New-DemoCertificate.ps1 -LanIp 10.9.9.86
   ```

3. 将 `.local/certs/harvest-demo-ca.crt` 传到自己的安卓演示设备，在“设置 → 安全/加密与凭据 → 安装证书 → CA 证书”安装。不同手机菜单名称可能不同。电脑如需使用 HTTPS，也需手动信任该演示 CA。脚本不会自动修改设备或 Windows 的信任设置。不要分享 `.local/certs` 中的私钥。
4. 停止服务并用 HTTPS 启动：

   ```powershell
   .\scripts\Stop-Demo.ps1
   .\scripts\Start-Demo.ps1 -Https
   ```

5. 手机 Chrome 打开 `https://电脑IPv4:4173`，确认没有证书错误；如果 Windows 防火墙拦截，仅为本地 Wi-Fi 演示允许相应端口访问。
6. 首次联网登录，等待资料下载和离线页面准备完成，可添加到桌面。关闭手机 Wi-Fi/流量后录入、刷新或关闭后重开，记录仍应在本机；回到相同 Wi-Fi 后自动同步。

更换电脑 IP 后重新生成服务端证书。CA 不变时无需重复安装 CA。HTTP 与 HTTPS、不同 IP 与端口是不同浏览器存储来源，切换入口前先同步旧入口的待传数据。

手机实机的证书安装与离线行为需要在目标设备验证；本项目已通过桌面 Chrome 的安卓宽度模拟、HTTP localhost 离线重开及 HTTPS 安全上下文离线重开测试。

## 退出登录

电脑和手机都可点击页面右上角“退出登录”，侧栏也保留退出入口。退出后回到登录页，刷新不会自动恢复登录；有待同步操作时先提示，确认退出后记录仍保留给原账号。离线也能退出，联网时同时注销后台会话。

## 已实现的业务规则

- 类别只负责归类，计费规则逐产品配置。老板设置阶段、方式、单价、条件、上限、优先级，试算后发布，记录员按配置录入。
- 单一计件/计重；多阶段分段填写；或老板配置每筐标准重量后按总重量顺序分配。组合方式不会对同一份完整工作量重复计薪。系统不执行任意自定义脚本，实际配置能力见[需求与实现范围](docs/requirements.md)。
- 斤、公斤、克换算；筐数步长为 0.5；单条记录合计金额四舍五入到分，再汇总工资。
- `工人 + 实际日期 + 上午/下午 + 产品` 数据库唯一；数量增加修改原记录，误删恢复原记录。
- 记录员可协作更正全部历史记录，往日及已结算更正原因必填；记录员可删除本人录入的任何未结算记录，不限日期；已纳入结算的记录任何人都不能直接删除，错误通过数量更正留痕。往日漏记由记录员补录、说明必填。
- 有网直接提交，明确拒绝在当前页面说明原因；断网、连接失败或结果未知才显示为待同步操作，恢复后按原操作 ID 自动补传；不同账号本机数据隔离。
- 原子 SQL 乐观锁校验 version；冲突保留表单，显示最新修改人及数量，可基于最新内容重改或确认原输入重提，每次仍校验版本。
- 指定月份追溯涨价，离线晚传同样按实际采摘日期适用；降价自指定日期生效；已确认账单保留，变化另行补结算。
- 按月确认结算快照；晚传记录/更正/调价形成待结算差额。结算与记录写入共用月份行锁，避免并发漏算。
- 手工登记线下分次付款，显示已确认应发、已付、未付。可导出工资表和结算快照 `.xlsx`。
- ECharts 展示产品排行、工人占比、每日趋势，可切换工资金额、计重公斤、计件筐数。
- 新增、修改、删除、恢复、规则发布、结算及付款均保留操作者与操作前后值。

## 验证与演示

每次改进后运行完整回归，新功能和缺陷修复需增加对应测试并纳入回归。在项目根目录执行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Regression.ps1
```

该命令自动准备独立 MySQL、后端和浏览器测试环境，执行所有自动化用例及构建检查，生成分批报告并关闭测试服务。测试不连接演示业务库。详见 [全量回归说明](docs/regression-testing.md)、[覆盖清单](docs/regression-coverage.md)及[设备人工验收](docs/regression-manual-checklist.md)。以下分步命令仅用于专项排查，不能代替完整回归。

```powershell
# 后端，默认使用独立内存测试库
$env:JAVA_HOME='D:\jdk21\jdk-21.0.10'
Set-Location backend
& 'D:\apache-maven-3.9.12\bin\mvn.cmd' test

# 前端本机队列测试与构建
Set-Location ..\frontend
npm.cmd ci
npm.cmd test
npm.cmd run build

# 浏览器测试：先启动本地演示，再运行；需要已安装 Google Chrome
npm.cmd run test:e2e
```

真实 MySQL 测试使用专用 `harvest_test` 库，测试代码明确限制可清理库名，不清理 `harvest`。详见[验证记录](docs/verification.md)。浏览器测试会在演示库新增带 `E2E-` 工号的测试工人与测试记录。

完整演示脚本见 [docs/demo.md](docs/demo.md)，包含断网、重传、唯一键冲突、两人编辑、补结算与付款。

## 源码入口

```text
backend/src/main/java/cn/farm/       后端领域服务与 API
backend/src/main/resources/db/migration/  数据库初始化/升级 SQL（Flyway 自动执行）
backend/src/test/                   事务、权限、并发与金额测试
frontend/src/components/            响应式业务页面
frontend/src/data.ts                IndexedDB 队列、缓存及同步确认
frontend/e2e/                      浏览器验收测试
scripts/                           本地启动、停止、证书生成
docs/requirements.md               当前实现规则与边界
docs/architecture.md               数据关系与同步流程
docs/screenshots/                  实际运行截图
```

本次交付为可运行原型和演示材料，完整毕业论文正文另行处理。浏览器本机存储不是备份；不承诺锁屏或关页后持续同步，清理站点数据前务必完成同步。
