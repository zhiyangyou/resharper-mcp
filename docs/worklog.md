# ReSharper MCP —— 工作日志

> 本文件记录各迭代的需求、方案、决策与验证结果。持续更新，作为当前现状的描述。

---

## 2026-08-16 迭代：Monitor 日志过滤 + 部署流程封装

### 需求

1. **Monitor 窗口应过滤掉 `internal/monitor` 等协议接口的日志**，需要新增过滤器机制处理这类业务。
2. **封装部署步骤**：当用户说「部署」时，应先杀掉所有 Rider 进程，再执行构建安装脚本。此规则写入本工作日志文件。

### 问题根因

`RequestKind` 枚举默认值为 `Local = 0`。`McpHttpServer.HandlePost` 埋点时只对 `tools/call` 填充了 `Kind`（由 `HandleToolCall` 设为 `Local`/`Forwarded`），而 `initialize`、`tools/list`、`internal/status`、`internal/monitor` 等协议请求**从未赋值 `Kind`**，因此全部以默认值 `Local` 进入日志 → 混入「本地请求」Tab。尤其 `internal/monitor` 每 5 秒轮询一次 + SSE 重连，刷屏严重。同时 `counts.local` 统计也被这些协议请求污染。

### 方案

| 层级 | 改动 |
|------|------|
| 后端 | `HandlePost` 对非 `tools/call` 请求统一标记 `entry.Kind = RequestKind.Other`（协议级请求不再以 Local 落库，统计也随之正确） |
| 前端 | `McpMonitorService` 新增**过滤器机制**：`logFilter` 谓词（默认只保留 `method == "tools/call"`），在 `poll()` 与 `onSseLog()` 两个入库入口统一应用；过滤掉的条目仍推进 `lastIndex`（防止重放），但不进共享快照、不通知 UI。此为整个 Monitor UI 的**单一过滤点**，状态栏与工具窗口共用 |
| 部署 | 新增 `deploy-rider.sh`：先 `pkill` 所有 Rider 进程，再执行 `install-rider.sh`；在项目 `CLAUDE.md` 挂载「部署 = 执行 deploy-rider.sh」约定 |

### 决策记录

- **过滤放前端而非后端**：后端 `RequestLogBuffer` 是进程级通用日志（对 `internal/monitor` 拉取本身也自洽），保持记录一切请求；展示侧（前端）按需过滤，职责清晰，且不破坏 `counts` 统计语义。前端过滤点收敛在 `McpMonitorService`，而非两个 Tab 各自过滤。
- **过滤在入库时应用**（而非 Tab 渲染时）：内存里根本不保存协议日志，避免污染；`lastIndex` 仍正常推进，避免被过滤的条目在轮询中反复返回。
- **`counts` 仍全量统计**：Tab 与过滤器只影响日志列表展示，不改变 `counts` 累计语义（此前 internal 请求错算进 `local` 的 bug 已由后端 Kind=Other 修复）。

### 涉及文件

- `src/ReSharperMcp/McpHttpServer.cs`（后端埋点）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpMonitorService.kt`（前端过滤器机制）
- `deploy-rider.sh`（新增，部署脚本）
- `CLAUDE.md`（挂载部署约定）
- 本文件（工作日志）

### 部署规则（长期约定）

> **用户说「部署」时**：先执行 `deploy-rider.sh`（该脚本会先杀掉所有 Rider 进程，再执行 `install-rider.sh` 完成构建与安装），随后提示用户重启 Rider 生效。

---

### Git 提交约定（长期约束）

> **所有 Git 提交的 message 必须使用中文书写**（包括 commit 标题与正文）。提交中允许保留必要的英文专有名词（如工具名、类名、标识符），但表述本身应为中文。
