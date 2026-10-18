# 项目测试与维护规则

以下规则由用户于 2026-10-18 确认，适用于本仓库后续功能开发、改进及缺陷修复。

- 每次改进完成后，从根目录执行 `powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Regression.ps1`，运行完整自动化回归。必选测试未执行、跳过、失败或环境缺失时，不得报告回归通过。
- 新增功能必须补充正常流程、权限及相关异常/边界测试，并登记到 `docs/regression-coverage.md`。前端单元测试放在 `frontend/src/**/*.test.ts`，浏览器/API 测试放在 `frontend/e2e/*.spec.ts`，后端测试使用 Maven 默认发现的 `*Test.java` 命名，使统一入口自动收录。
- 缺陷修复必须补充能复现问题的回归用例。遵循用户最新确认的业务规则；涉及规则冲突或改变现有业务行为时，先向用户确认。
- 不以跳过用例、删除有效断言或反复重试掩盖失败。业务规则经确认调整时，同步更新需求、断言和覆盖清单。
- 回归仅使用独立测试环境及数据。不得将测试清理、测试写入或测试服务代理指向演示业务库 `harvest`。统一入口拒绝复用被占用的专用端口。
- 每轮保留报告、日志和失败证据，最终反馈说明实际运行结果和未验证范围。
- 安卓实机、目标设备证书信任及实体打印按 `docs/regression-manual-checklist.md` 验收；变更影响这些行为时执行对应人工检查，未执行时明确标记未验证。

执行说明见 `docs/regression-testing.md`；现行业务规则见 `docs/requirements.md` 及其引用的最新确认稿。
