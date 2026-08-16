# ReSharper MCP 监控 Tool Window —— 需求与实现计划

> 本文档记录该需求的完整定义、实现计划、已确认的约束，以及尚未做的事。后续迭代以此文档为准。
>
> 状态：**需求已确认，等待实现** · 日期：2026-08-16

---

## 1. 需求概述

在 Rider 内部实现一个 **Tool Window（工具窗口）**，用于监控当前 MCP 服务器的运行状态与请求日志。

### 1.1 核心功能

| 功能 | 说明 |
|------|------|
| 主状态区 | 显示当前进程是否 Primary、端口、在线状态 |
| Tab 1「本地请求」 | 本进程作为 MCP 服务器收到的所有 `tools/call` 请求 —— 参数、返回结果、耗时 |
| Tab 2「路由请求」 | 本进程作为 Primary 时**转发给 Peer** 的代理请求 —— 时间、请求内容、返回结果 |
| Tab 置灰 | **非 Primary 时 Tab 2 置灰不可交互** |
| 请求详情 | 点击表格行弹出详情对话框，以**编辑框**形式展示参数与返回结果的**全部内容** |
| 实时性 | 轮询 + SSE 混合：SSE 推送实时日志事件，断线后靠轮询重播 |

### 1.2 两个 Tab 的精确语义（已确认）

- **Tab 1「本地请求」** = 本进程执行的 `tools/call`，包含两类（由来源列区分）：
  - **客户端直连**：MCP 客户端直接向本进程发起
  - **被代理进来**：Primary 把请求转发到本进程（Peer 视角）
- **Tab 2「路由请求」** = 本进程作为 Primary 时转发给 Peer 的代理请求

> 一次 Primary→Peer 的代理调用会在两个进程各产生一条日志：Primary 进程记 `forwarded`（进 Tab 2），Peer 进程记 `local` + `viaPrimary=true`（进 Tab 1）。

---

## 2. 已确认的决策（质询结论）

| # | 决策点 | 结论 |
|---|--------|------|
| D1 | 两个 Tab 的语义 | **本地 vs 转发**：Tab 1 = 本进程执行的所有请求；Tab 2 = Primary 转发给 Peer 的请求 |
| D2 | UI 载体 | **Tool Window**（JetBrains 工具窗口，非 Editor 标签页） |
| D3 | 日志保留策略 | **内存环形缓冲**（约 500 条），表格行显示摘要 |
| D4 | 日志获取方式 | **轮询 + SSE 混合**：轮询为兜底真值，SSE 推送实时日志事件，断线重连后按 index 重播 |
| D5 | 日志存储位置 | **每个进程各自本地存**（缓冲在 `McpShellComponent` 进程级持有，提升时历史不丢） |
| D6 | 前端解析方式 | **Jackson 结构化解析**（重构现有状态栏的手写正则） |
| D7 | 详情框内容 | **显示全部内容**，以**编辑框**形式展示（推翻最初的 200 字符截断方案） |
| D8 | 代理来源标记 | **加 `Mcp-Proxy` 头区分**：客户端直连 vs Primary 转发（表格增加来源列） |

### 2.1 由 D7 引起的调整

原方案"存储时截断到 200 字符"被推翻。新方案：

- **后端存储完整内容**：参数与返回结果不截断（或截断到很大的上限，如 2000~5000 字符，避免单条超大结果撑爆内存）
- **表格行显示摘要**：约 60~200 字符预览，带"已截断"标记
- **详情对话框**：以只读编辑框展示存储的**完整**参数与返回结果（可选中复制）
- 内存估算：500 条 × 平均 4KB ≈ 2MB，可接受

---

## 3. 现状调查（代码库事实）

### 3.1 前端（rider-plugin，Kotlin）

- **已有**：`McpStatusBarWidget`（状态栏小部件）+ `McpStatusBarWidgetFactory`，通过 5 秒轮询 HTTP POST `internal/status` 获取端口/角色/解决方案，用**手写正则**解析，端口读 `RESHARPER_MCP_PORT` 环境变量（默认 23741）
- **没有**：ToolWindow、action、service、listener、任何 Swing/JPanel UI 代码
- **依赖已覆盖**：`rider("2025.3")` 已含全部 ToolWindow API（ToolWindowManager、ToolWindowFactory、ContentFactory、SimpleToolWindowPanel）及 Jackson，**无需改 build.gradle.kts**
- **关键文件**：
  - `rider-plugin/src/main/resources/META-INF/plugin.xml`（唯一注册是 `<statusBarWidgetFactory>`）
  - `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpStatusBarWidget.kt`（正则解析 L125-150、own httpPost L170-188、own 5s 轮询 L102-123）
  - `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpStatusBarWidgetFactory.kt`
  - `rider-plugin/build.gradle.kts`

### 3.2 后端（src/ReSharperMcp，C# net472 + Newtonsoft.Json）

- **请求链路**：`ListenLoop`（后台线程）→ `ThreadPool.QueueUserWorkItem(HandleRequest)` → `HandlePost` → `ProcessRequest` → `HandleToolCall` / `ProxyToPeer`
- **最佳日志包络点**：`HandlePost`（L282-314）—— 反序列化请求 → 处理 → 序列化响应，天然包裹整个请求生命周期，现有 `_logger.Verbose`（L290、L307）无耗时、无本地/代理标记
- **`internal/status`**（L816-851）：返回 `{port, role, solutions:[{name,path}]}`，状态栏唯一消费方
- **SSE**：`HandleGetSse`（L321-359）目前只发 keepalive，不推消息
- **提升机制**：`TryPromoteToPrimary`（L228-239）会**新建** `McpHttpServer` → 日志缓冲若在服务器内会丢历史，必须由 `McpShellComponent` 进程级持有
- **注册表**：`_solutions` / `_peers` 字典，`lock(_lock)` 保护
- **线程模型**：请求处理在 ThreadPool 线程并发 → 日志记录器必须线程安全；计时用 `Stopwatch`；不要依赖 TLS 跨线程传上下文
- **关键文件**：
  - `src/ReSharperMcp/McpHttpServer.cs`（960 行）
  - `src/ReSharperMcp/McpShellComponent.cs`（277 行）
  - `src/ReSharperMcp/McpServerComponent.cs`（PSI 线程调度，不改动）
  - `src/ReSharperMcp/Protocol/JsonRpc.cs`、`Protocol/McpTypes.cs`

---

## 4. 实现计划

### 4.1 后端改动（src/ReSharperMcp/）

#### 新增 `RequestLogBuffer.cs` —— 环形缓冲 + 请求日志实体

```csharp
public enum RequestKind { Local, Forwarded, Other }

public sealed class RequestLogEntry
{
    public long Index;                 // 进程内单调递增，commit 时分配
    public long TimestampMs;           // UTC 毫秒
    public string Method;              // "tools/call" | "initialize" | ...
    public string Tool;                // tools/call 的工具名，否则 null
    public RequestKind Kind;           // Local / Forwarded / Other
    public long DurationMs;            // Stopwatch 计时
    public string Solution;            // 目标解决方案名（本地分支）
    public int PeerPort;               // >0 表示转发
    public bool ViaPrimary;            // Mcp-Proxy 头：true = 被 Primary 转发进来
    public string Args;                // 参数完整内容（供详情框）
    public string Result;              // 返回结果完整内容（供详情框）
    public string ArgsPreview;         // 表格行摘要（约 200 字符）
    public string ResultPreview;       // 表格行摘要
    public bool IsError;
    public string ErrorText;
    public bool ArgsPreviewTruncated;
    public bool ResultPreviewTruncated;

    public JObject ToJObject();        // 统一序列化（monitor 响应 + SSE 共用）
}

public sealed class RequestLogBuffer
{
    private const int Capacity = 512;
    private const int PreviewLength = 200;
    private const int MaxStoredLength = 5000;   // 详情框完整内容上限（防单条超大结果）
    // ring: RequestLogEntry[] + _head + _count + _nextIndex
    // counts: long[3] + _errors
    // subscribers: List<Action<RequestLogEntry>> + _lock

    public RequestLogEntry Begin();
    public long Commit(RequestLogEntry e);       // 锁内分配 index、写 ring、更新统计；锁外通知订阅者
    public List<RequestLogEntry> Query(long after, int limit);
    public void GetStats(out long[] counts, out long errors, out long nextIndex);
    public IDisposable Subscribe(Action<RequestLogEntry> onEvent);
}
```

#### 新增 `SseSink.cs` —— 每 SSE 连接一个写入器

```csharp
internal sealed class SseSink : IDisposable
{
    // 自己的 _lock + _dead 标记；Push(RequestLogEntry) 写 "event: log\ndata: {json}\n\n"
    // keepalive 循环也走同一锁；写失败置 _dead
}
```

#### 修改 `McpHttpServer.cs`

| 位置 | 改动 |
|------|------|
| 构造函数（L29-36） | 增加 `RequestLogBuffer requestLogs` 参数 |
| `HandlePost`（L282-314） | 包络：`Begin()` → 填 Method/Tool/Args → `Stopwatch` → `ProcessRequest(request, entry)` → 填 DurationMs/Result/IsError → `Commit()` |
| 通知分支（L295-301） | 处理完填 `Kind=Other` 后 Commit，返回 202 |
| `ProcessRequest`（L371-423） | 签名加 `entry`；新增 `case "internal/monitor"` |
| `HandleToolCall`（L503-632） | 签名加 `entry`；锁内解析出 `targetSolution`；转发分支设 `Kind=Forwarded, PeerPort`；本地分支设 `Kind=Local` |
| `HandleInternalMonitor` | 新增：返回 port/role/online/nextIndex/counts/solutions/logs |
| `HandleGetSse`（L321-359） | 每连接建 `SseSink` 并订阅缓冲，keepalive 保留，finally 中退订 |
| `ProxyToPeer`（L634-689） | 转发时附加 `Mcp-Proxy: 1` 请求头（约 2 行） |

#### 修改 `McpShellComponent.cs`

- 新增字段 `private readonly RequestLogBuffer _requestLogs = new RequestLogBuffer();` —— **进程级持有，提升时历史不丢**
- 两处 `new McpHttpServer(...)`（构造 L39、提升 L228）传入同一缓冲
- **端口侧车文件**：绑定成功后写 `Path.Combine(Path.GetTempPath(), $"resharper-mcp-port-{Environment.ProcessId}.txt")`，`Dispose()` 与提升成功时删除/重写 —— 让 Peer 前端能找到自己进程的实际端口（提升后端口会变）

### 4.2 JSON-RPC 契约

#### `internal/monitor`（新增）

```json
// 请求
{ "jsonrpc": "2.0", "id": 1, "method": "internal/monitor",
  "params": { "after": 0, "limit": 200 } }
//   after  = 客户端已见的最大 index，用于增量拉取/重播
//   limit  = 最大返回条数，钳制到 500

// 响应
{ "jsonrpc": "2.0", "id": 1, "result": {
    "port": 23741, "role": "primary", "online": true,
    "serverTime": 1723776000000, "nextIndex": 42,
    "counts": { "local": 5, "forwarded": 2, "other": 30, "errors": 1 },
    "solutions": [ { "name": "MyProject", "path": "/Users/x/MyProject.sln" } ],
    "logs": [
      { "index": 40, "ts": 1723776000000, "method": "tools/call",
        "tool": "find_usages", "kind": "forwarded", "viaPrimary": false,
        "durationMs": 12, "solution": "OtherRepo", "peerPort": 23742,
        "args": "{...完整...}", "result": "...完整...",
        "argsPreview": "...200字摘要...", "resultPreview": "...200字摘要...",
        "isError": false, "errorText": null,
        "argsPreviewTruncated": false, "resultPreviewTruncated": true }
    ]
} }
```

`internal/status` 保持不动（watchdog 和旧消费者继续用）。`internal/monitor` 为新增，纯加法。

#### SSE 事件格式

```
event: log
data: {"index":41,"ts":...,"method":"tools/call","tool":"search_symbol",...,"result":"...完整...","resultPreview":"..."}

```

- 每条已提交日志推送一个事件；keepalive（`: connected` / `: keepalive`）不变
- 事件内 `index` 单调递增，客户端丢弃 `index <= lastIndex` 的重复；断线重连后调 `internal/monitor(after=lastIndex)` 重播缺口；轮询兜底

### 4.3 前端改动（rider-plugin，Kotlin）

| 文件 | 操作 | 职责 |
|------|------|------|
| `McpModel.kt` | 新增 | `Role`、`LogKind`、`MonitorState`、`RequestLogEntry`、`MonitorSnapshot` 数据类 |
| `McpMonitorClient.kt` | 新增 | HTTP + SSE 传输、Jackson 解析、端口发现（环境变量 → PID 侧车文件） |
| `McpMonitorService.kt` | 新增 | `@Service(Service.Level.PROJECT)` 轻量服务：5 秒轮询 + SSE 重连 + 共享状态 + 监听器列表；实现 `Disposable` |
| `McpToolWindowFactory.kt` | 新增 | 实现 `ToolWindowFactory`，在 `createToolWindowContent` 里建面板 |
| `McpToolWindowPanel.kt` | 新增 | 主状态条 + `JBTabbedPane` 两个 Tab + 两个 `JBTable` + Tab2 置灰逻辑 + 双击弹详情 |
| `McpLogsTableModel.kt` | 新增 | `AbstractTableModel`，按 kind 过滤 + index 排序 |
| `RequestDetailDialog.kt` | 新增 | `DialogWrapper`：只读编辑框展示完整参数/结果 + 元数据 |
| `McpStatusBarWidget.kt` | 修改 | 改为消费共享 service（删除 own 轮询/httpPost/正则） |
| `McpStatusBarWidgetFactory.kt` | 修改 | `createWidget(project)` 传 project |
| `plugin.xml` | 修改 | 注册 `<toolWindow id="ReSharperMcp.Monitor" .../>` |

**核心类签名**（关键节选）：

```kotlin
// McpToolWindowPanel
class McpToolWindowPanel(project: Project, toolWindow: ToolWindow)
    : SimpleToolWindowPanel(true, false), Disposable {
    private val service = project.service<McpMonitorService>()
    // 状态条: role/port/online JBLabel + Restart 按钮
    // localModel  = McpLogsTableModel { it.kind == LogKind.LOCAL }
    // routedModel = McpLogsTableModel { it.kind == LogKind.FORWARDED }
    // 双击行 → RequestDetailDialog(project, entry).show()
    // 订阅 service，invokeLater 回 EDT 刷新模型/状态条/Tab 可用性
    private fun updateTabAvailability() {
        tabs.setEnabledAt(1, latestState.role == Role.PRIMARY)  // 非 Primary 置灰
    }
    override fun dispose() { subscription?.close() }
}

// 表格列：Time | Tool | Duration | Target | Source | Result | Error
//   Target = solution 名 | "peer:23742"
//   Source = "direct" | "viaPrimary"（D8 决策，仅在 local 时显示）
```

**Tab 2 置灰逻辑**：`JBTabbedPane.setEnabledAt(1, isPrimary)`，由共享 service 的 role 状态驱动；Peer 提升为 Primary 时自动启用。

### 4.4 前端状态流

```
McpMonitorClient（传输层）──fetchMonitor──► McpMonitorService（共享状态）
        ▲                                    │  register(listener)
        └────SSE log 事件───────────────────►  ├─ McpStatusBarWidget（状态栏）
                                               └─ McpToolWindowPanel（工具窗口）
```

- service 单例由 project 持有，状态栏与工具窗口共用 → **不重复轮询、不重复建连接**
- 所有 UI 变更经 `invokeLater` 回 EDT
- 面板经 `Disposer.register(toolWindow.contentManager, panel)` 随 project 释放

---

## 5. 实施阶段与验证点

### Phase 0 —— 后端环形缓冲 + 查询端点
新增 `RequestLogBuffer`、注入服务器、新增 `internal/monitor`（空日志 + 统计 + 解决方案）。
**验证**：`curl internal/monitor` 返回 `role/port/nextIndex:0/logs:[]`；`internal/status` 仍正常（watchdog 不受影响）。

### Phase 1 —— 后端埋点 + SSE 推送
包络计时/提交、`HandleToolCall` 分支标记、`HandleInternalMonitor` 日志序列化、`HandleGetSse` 订阅、`Mcp-Proxy` 头。
**验证**：
- 单进程：跑一次 `tools/call` → `internal/monitor` 出现一条 `kind:"local"` 且带完整 args/result；`curl -N GET /` 收到 `event: log`
- 双进程双解决方案：调 Peer 目标 → Primary 的 monitor 出现 `kind:"forwarded"`+`peerPort`，Peer 的 monitor 出现 `kind:"local"`+`viaPrimary:true`
- 提升：关掉 Primary → Peer 接管基础端口 → 其 monitor 历史/nextIndex 不丢

### Phase 2 —— 共享客户端 + service + 状态栏重构
新增 `McpModel`、`McpMonitorClient`（Jackson）、`McpMonitorService`；状态栏改为消费 service。
**验证**：状态栏显示端口/角色/离线正确，弹窗与 Restart 正常；确认 `ObjectMapper` 可加载。

### Phase 3 —— Tool Window
注册 toolWindow + 工厂 + 面板 + 表格 + 详情对话框 + Tab2 置灰。
**验证**：打开"MCP Monitor"工具窗口；状态条显示角色/端口/在线；Tab1 实时列出本地请求（SSE 驱动）；Peer 下 Tab2 置灰、Primary 下可用且有数据；双击行弹出含完整内容的详情编辑框；关闭 project 无线程泄漏。

### Phase 4 —— 健壮性 + 打磨
断线重连/重播（杀后端再重启 → 按 `after` 补拉）、提升中途端口翻转、超大结果截断标记、列宽、时间戳格式、（可选）"清空"按钮（仅前端）。

---

## 6. 约束（Constraints）

1. **进程级日志、易失**：不持久化，不做跨进程聚合。Primary 的 Tab2 和 Peer 的 Tab1 只显示本进程后端记录的日志。（在 Primary 窗口看 Peer 日志需新增 `internal/monitor` 向 Peer 扇出 —— 明确**不在本期范围**）
2. **详情存储上限**：后端存储完整内容但设上限（约 5000 字符/字段，防单条超大结果撑爆内存），超过上限截断并标记。表格行摘要约 200 字符。
3. **SSE 只推日志事件**，不推工具结果流；事件 payload 有上限（大结果只推预览/截断版，完整内容靠轮询拿）。
4. **`internal/status` 保持兼容**：watchdog 与旧消费者继续使用，`internal/monitor` 为纯新增；JSON-RPC 版本与 MCP 协议协商不变。
5. **不改 `McpServerComponent` 与任何工具**：埋点全在 `McpHttpServer` / `McpShellComponent`。
6. **不加依赖**：前端 `rider("2025.3")` 已含全部所需 API 与 Jackson；后端 net472 + Newtonsoft.Json 已具备。
7. **线程安全**：后端缓冲所有方法锁保护；工具执行在 `_lock` 外；SSE 推送经 sink 自己的锁写、在缓冲锁外执行。前端所有 UI 变更回 EDT。
8. **前后端契约稳定**：`RequestLogEntry.ToJObject()` 统一序列化，monitor 响应与 SSE 事件字段一致；前端 `McpModel` 与之一一对应。

---

## 7. 未做的事（Non-goals / 待办）

| 项 | 状态 | 说明 |
|----|------|------|
| 跨进程日志聚合 | 未做 | 在 Primary 窗口集中查看所有 Peer 的日志（需 `internal/monitor` 向 Peer 扇出） |
| 日志持久化/落盘 | 未做 | 缓冲重启即丢；可选后续做文件日志 + 前端按需读取 |
| 详情框显示"超上限被截断"的完整原始内容 | 未做 | 存储上限 5000 字符仍可能截断超大结果；如需完整需流式/懒加载方案 |
| 表格排序/过滤/分页 | 未做 | 本期只有按 kind 过滤的两个 Tab，无其他交互控件 |
| 导出（CSV/JSON） | 未做 | 无导出需求 |
| 设置 UI | 未做 | 无端口/缓冲大小/刷新间隔配置界面（`CLAUDE.md` 已有此长期 TODO） |
| 认证/TLS | 未做 | 不影响本期 |
| "清空"按钮 | 可选 | 仅前端清列表，后端缓冲不动 |
| 状态栏弹窗顺带打开工具窗口 | 可选 | 后续可在 widget 弹窗加"打开监控"入口 |
| 前端 SSE 复用连接确认 | 待验证 | 需实测 SSE 流是否多客户端共享（当前 GET 每连接独立流） |

---

## 8. 关键文件

### 后端
- `src/ReSharperMcp/RequestLogBuffer.cs`（新增）
- `src/ReSharperMcp/SseSink.cs`（新增，或内嵌 McpHttpServer.cs）
- `src/ReSharperMcp/McpHttpServer.cs`（修改）
- `src/ReSharperMcp/McpShellComponent.cs`（修改）

### 前端
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpModel.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpMonitorClient.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpMonitorService.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpToolWindowFactory.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpToolWindowPanel.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpLogsTableModel.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/RequestDetailDialog.kt`（新增）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpStatusBarWidget.kt`（修改）
- `rider-plugin/src/main/kotlin/com/jlight/resharpermcp/McpStatusBarWidgetFactory.kt`（修改）
- `rider-plugin/src/main/resources/META-INF/plugin.xml`（修改）

### 文档
- 本文档：`docs/mcp-monitor-toolwindow-requirement.md`
