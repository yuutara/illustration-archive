# Illustration Archive - Project State

> 只记录当前状态，是阶段判断的权威来源；与仓库实际不一致时以仓库为准。上限约 6 KB。
> 工作包细节见 [DESKTOP_POC](DESKTOP_POC.md)，桌面开发流水见 [PROJECT_HISTORY](PROJECT_HISTORY.md#桌面产品化流水)。
> 最后更新：2026-11-02。

## 阶段与分支

- 最新发布：`v0.5.0`（`396ed46`）。阶段：**Windows 桌面产品化**，路线见 [DESKTOP_ROADMAP](DESKTOP_ROADMAP.md)。版本号与发布由用户决定。
- 开发分支：`poc/windows-desktop`。`origin/master` 为 `a4f434c`，本地 `master` 停在 `a40ece5`。分支现状以 Git 为准。
- Flyway：V1–V8，MySQL 与 H2 各一份。真实图库仍是 V7，下次用新版启动时自动升级；MySQL 版 V8 未运行过。
- 最近全量验证（2026-11-02，JDK 21）：Java 502 tests，0 failures，19 skipped（默认关闭的 MySQL / S3 测试）；JS 389/389。

## 已签收

工作包 A（X 同步与 OAuth 安全）、B（图库交互与媒体资源）、C 第一步（备份副本升级与恢复）、配置与连接收尾；独立副本的真实 X 授权与同步（未全范围）。

## 当前授权

| 事项 | 范围 | 不包含 | 失效条件 |
| --- | --- | --- | --- |
| 真实 X 读取 / 同步（2026-11-02） | `D:\Temp\ia-x-live-acceptance-20261102\home` 这个已登录副本上的必要验收；使用现有余额，不再询问费用上限 | 充值、自动充值、账单设置 | 换副本、换账号或用户收回 |
| 历史进度归属（2026-11-02） | 该副本上已确认同一账号，不再询问 | 重置进度（需业务决定） | 换账号 |

真实 Gemini、原图库升级或替换、真实 MySQL 写入、删除数据均未授权。

## 待验收

用户手动的 B 级项尚未开始。手动验收用桌面快捷方式“插画档案 手动验收”打开独立副本；不要直接双击 EXE，否则会打开原图库并升级到 V8。该副本关窗即退出，测托盘前先在设置页改“关闭窗口时”。

| 项 | 级别 | 负责 | 备注 |
| --- | --- | --- | --- |
| 托盘菜单“退出”、注销 / 关机 | B | 用户 | 代码路径与询问框退出相同 |
| 物理拖放、文件选择框 | B | 用户 | |
| 真实图库上的观感与手感（静展、批量整理、对话框） | B | 用户 | 发布前集中过一遍 |
| 缺少 WebView2 时的回退 | C | Codex | 需要无 WebView2 的环境 |
| 真实 token 过期刷新 | D | 用户 + Codex | 不手工改 token 或过期时间 |
| 真实 Gemini 调用 | D | 用户 | 未授权 |
| 原图库首次升级到 V8 | D | 用户 | 未授权，V7 备份已保留 |
| MySQL 版 V8 | C | Codex | 从未在 MySQL 上运行 |
| X 全范围扫描 | D | Codex | Recent ACTIVE / History MORE，费用已授权 |
| R3.1 安装、升级、卸载 | C | Codex | 首轮 2 项已处理，只复验受影响路径；见 `docs/handoffs/NEXT.md` |
| 安装向导、快捷方式、安装后双击 | B | 用户 | Codex 验完后，用桌面“插画档案 安装验收”和“安装后启动”；不要从开始菜单打开（会进原图库并升级），验完卸载 |

## 重要限制

- 无用户鉴权，仅供本机使用，不暴露到公网或不可信局域网。
- 整数 / 布尔配置写成空值会导致 MySQL 模式启动失败，不用时删除该行。
- X API 按量计费；Likes 接口没有 `since_id` 或点赞时间，同步结果是“按 API 观察衔接”。History 旧游标可能失效，不能静默重置。
- Gemini 模型固定为 `gemini-3.1-flash-lite`，图片会发送到 Google。
- 2026-10-15 的 SQL 备份是 V7 状态。LocalStack 只用于临时实验。

## 下一步

1. R3.1（`ffd1983`）C 级首轮 2 项已处理：构建临时目录误删已修复；默认 TEMP 启动失败定位为宿主环境问题，Explorer 启动正常。用户提交后交 Codex 复验受影响路径；实际安装仅用 D 盘。[现状与证据](DESKTOP_POC.md#安装器r312026-11-02)，交接见 `docs/handoffs/NEXT.md`。
2. 未排期候选：启动与本地安全小加固、依赖补丁更新、V0.5 遗留 P1。

## 文档导航

| 文档 | 何时阅读 |
| --- | --- |
| [PROJECT_MAP](PROJECT_MAP.md) | 用户视角的路线、能力进度、待决定事项和想法池 |
| [README](../README.md) | 涉及运行、API 或设计 |
| [DESKTOP_ROADMAP](DESKTOP_ROADMAP.md) | 规划桌面工作 |
| [DESKTOP_POC](DESKTOP_POC.md) | 涉及桌面架构、H2 或打包 |
| [X_LIKES_SYNC_CONSISTENCY](X_LIKES_SYNC_CONSISTENCY.md) | 涉及 X Sync |
| [PROJECT_HISTORY](PROJECT_HISTORY.md) | 追溯历史决策或证据 |
