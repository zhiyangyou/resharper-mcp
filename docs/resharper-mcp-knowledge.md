# ReSharper MCP 实现知识文档

本文档汇总 ReSharper MCP 服务器的架构设计与实现细节，供后续引用和查询。内容包括：整体架构、多进程主/从机制、HTTP 传输层、PSI 线程调度、工具系统、符号解析，以及关键源码片段。

相关图（`docs/` 目录，archify 生成的可交互 HTML）：

- [架构总览](resharper-mcp-architecture.html)
- [tools/call 处理流程](resharper-mcp-toolcall-workflow.html)
- [多解决方案调用时序](resharper-mcp-sequence.html)
- [代码智能数据流](resharper-mcp-dataflow.html)
- [主/从服务器生命周期](resharper-mcp-lifecycle.html)

---

## 1. 项目是什么

一个 **MCP（Model Context Protocol）服务器**，运行在 ReSharper/Rider 后端进程内部，通过 HTTP 向 AI 助手暴露代码智能能力（查找引用、符号信息、代码补全、重命名、快速修复等）。

技术要点：

- Rider 插件由 **JVM 前端 + .NET 后端** 两部分组成。本仓库的 `rider-plugin/META-INF/plugin.xml` 是最小的 IntelliJ 插件描述符（纯后端插件不需要 Java/Kotlin 代码），没有它 Rider 会静默忽略 `dotnet/` 目录。
- .NET 后端 `src/ReSharperMcp/` 目标框架为 **net472**（ReSharper 插件必须）。
- 官方 C# MCP SDK（`ModelContextProtocol` NuGet）要求 .NET 8+，不适用于 net472，因此**手写实现了轻量 MCP 服务器**：`HttpListener` 做 HTTP 传输，直接实现 JSON-RPC 2.0。
- 使用 `Newtonsoft.Json` 序列化（Rider 宿主自带，无需打包）。
- PSI 文件解析是**语言无关的**（`GetPsiFiles<KnownLanguage>()`），支持 C#、F#、VB 等任何有 ReSharper PSI 实现的语言。

---

## 2. 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│ Rider 进程 · Primary（首个实例，绑定 23741）                   │
│                                                             │
│  McpShellComponent ──创建/看门狗──> McpHttpServer (:23741)    │
│     （进程级单例）                    （JSON-RPC 2.0 路由）      │
│                                          │                    │
│  McpServerComponent <──RegisterSolution──┘                    │
│     （每解决方案一个）                    │                    │
│  ExecuteOnPsiThread ──读写锁调度──> ReSharper 工具层(24个工具) │
│                                          │                    │
│                                      ReSharper PSI 引擎      │
│                                                             │
├─────────────────────────────────────────────────────────────┤
│ Rider 进程 · Peer（后续实例，绑定 23742+）                     │
│  McpShellComponent + McpHttpServer                           │
│    └─ internal/register 上报 → Primary 记录路由                │
│    └─ 被 Primary 代理 tools/call                             │
└─────────────────────────────────────────────────────────────┘
```

核心组件：

| 组件 | 生命周期 | 职责 |
|------|----------|------|
| `McpShellComponent` | 进程级单例（`[ShellComponent]`） | 端口绑定与主/从判定、创建 `McpHttpServer`、处理 Peer 注册、看门狗 |
| `McpHttpServer` | 每进程一个 | `HttpListener` HTTP 服务器、JSON-RPC 2.0 路由、解决方案路由表、Peer 代理 |
| `McpServerComponent` | 每解决方案一个（`[SolutionComponent]`） | 注册/注销工具、把工具执行调度到 PSI 线程 |

---

## 3. 多进程主/从（Primary/Peer）机制

### 3.1 为什么需要它

Rider 每个解决方案开一个独立 OS 进程，多个解决方案 = 多个进程，会竞争端口。主/从模型让**所有 MCP 客户端只连一个固定端口（Primary）**，由 Primary 在内部路由到正确的进程。

### 3.2 工作方式

- **Primary**：第一个成功绑定基础端口（23741）的进程。它是唯一对外端点，内部维护路由表（本地解决方案 + 各 Peer 上报的解决方案与端口）。
- **Peer**：后续进程绑定 23742、23743…，通过 `internal/register` JSON-RPC 方法向 Primary 上报自己的端口、解决方案名、工具清单。Primary 对 Peer 的 `tools/call` 请求**代理转发**到对应端口。
- **看门狗**：每 30 秒运行一次。Primary 探活自己的监听器；Peer 探测 Primary 是否还活着，若主端口失效则尝试接管（重新绑定基础端口、转移注册、成为新 Primary）。
- **失效清理**：代理失败（Peer 崩溃）时自动移除过期注册。

### 3.3 路由规则（`McpHttpServer.HandleToolCall`）

```
tools/call 请求
  ├─ 工具名 == list_solutions  → 直接返回所有已打开的解决方案（含稳定 id，即完整 .sln 路径）
  ├─ 传入 solutionName：
  │    1. 精确名称 / 完整路径匹配（id 即完整路径，必命中且唯一）
  │    2. 唯一路径段匹配（如 "tps-project" 匹配 ".../tps-project/..."，不匹配 ".../tps-project-dyn/..."）
  │    3. 仍歧义 → 返回候选列表，提示复制 list_solutions 中的 id（完整路径）作为 solutionName
  ├─ 未传 solutionName：
  │    仅一个解决方案 → 自动路由（向后兼容）
  │    多个 → 报错并要求指定 solutionName
  └─ 目标为本地 → 本进程执行；目标为 Peer → HTTP 代理转发（转发请求携带已解析的 id，Peer 精确命中本地解，不会再次转发）
```

> **同名解决方案（同名不同路径）**：`solutionName` 只是显示名（`.sln` 文件名去扩展名），同名解会重名。内部注册以**完整路径**为 key，同名解可共存。客户端应优先从 `list_solutions` 复制目标解的 `id`（完整路径）作为 `solutionName` 精确定位；传 `solutionName: "MyProject"` 这类同名文件名会命中多个并返回歧义错误。

### 3.4 关键源码：端口绑定与主/从判定（`McpShellComponent.cs`）

```csharp
[ShellComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
public class McpShellComponent : IDisposable
{
    private const int DefaultPort = 23741;
    private const int MaxPortAttempts = 10;

    public McpShellComponent(Lifetime lifetime, ILogger logger)
    {
        var basePort = GetPort();   // RESHARPER_MCP_PORT 环境变量可覆盖

        // Try to bind the primary port; if taken, try subsequent ports
        for (var attempt = 0; attempt < MaxPortAttempts; attempt++)
        {
            var tryPort = basePort + attempt;
            var tryServer = new McpHttpServer(tryPort, logger);
            try
            {
                tryServer.Start();
                _server = tryServer;
                _isPrimary = (tryPort == basePort);   // 绑定到基础端口 = Primary
                _server.IsPrimary = _isPrimary;
                break;
            }
            catch (Exception)
            {
                tryServer.Stop();
                // 端口被占用，尝试下一个
            }
        }
    }
}
```

### 3.5 关键源码：Peer 向 Primary 注册（`McpShellComponent.NotifyPrimary`）

```csharp
private void NotifyPrimary(string method, string solutionName, string solutionPath, List<ToolDefinition> tools)
{
    var request = new JsonRpcRequest
    {
        Id = 1,
        Method = method,   // "internal/register" 或 "internal/deregister"
        Params = new JObject
        {
            ["port"] = _server.Port,
            ["solutionName"] = solutionName,
            ["solutionPath"] = solutionPath,
            ["tools"] = toolsArray
        }
    };
    SendToPrimary(request);   // POST 到 http://127.0.0.1:{primaryPort}/
}
```

### 3.6 关键源码：故障提升（`McpShellComponent.TryPromoteToPrimary`）

```csharp
private void TryPromoteToPrimary()
{
    try
    {
        PingServer(_primaryPort);   // 主端口还能 ping 通 → 保持 Peer
    }
    catch
    {
        // Primary 不可达 → 尝试接管
        var newServer = new McpHttpServer(_primaryPort, _logger);
        newServer.Start();
        newServer.IsPrimary = true;

        // 把旧服务器的所有解决方案注册转移到新服务器
        _server.TransferRegistrationsTo(newServer);

        _server = newServer;
        _isPrimary = true;
        _server.Stop();  // 停掉旧的 Peer 服务器
        // "Promoted to primary on port {_primaryPort}"
    }
}
```

### 3.7 常见问题解答

**Q：多开几个 Rider 进程，会起多个不同端口的 MCP 服务器吗？**

会。每个 Rider 进程都有自己的一份 `McpHttpServer`（23741、23742、23743…）。但 **Agent 只连 23741（Primary）** 就够了，路由是服务器内部的事。

**Q：Agent 能连到正确的 MCP 服务器吗？**

能。Primary 是唯一对外端点，根据 `solutionName`（或单解决方案自动路由）把请求派到正确进程：本地直接执行，Peer 走 HTTP 代理。

**Q：Primary 进程关闭了怎么办？**

剩下的 Peer 看门狗会探测主端口失效并尝试接管 23741。但**进程一重启，长连接（SSE/会话）就会断，Agent 需要重连**。

**Q：Primary 是谁？**

Primary 不是单独的组件，而是"**最先成功绑定基础端口 23741 的那个 Rider 进程里的 MCP 服务器**"所承担的角色。类比：Primary 是前台接线员，Agent 只打前台电话（23741）；Peer 是分机，前台负责转接。

---

## 4. 进程内单例的实现机制（McpShellComponent）

**重要澄清：`McpShellComponent` 是"进程内单例"，不是"进程间单例"。** 每个 Rider 进程有自己独立的 ReSharper 宿主和组件容器，物理隔离、不共享内存。多进程协同靠的是**端口竞争 + HTTP 通信**，而不是共享对象。

进程内单例靠 **JetBrains 组件容器（依赖注入/IoC 框架）** 实现：

```csharp
[ShellComponent(Instantiation.ContainerAsyncAnyThreadSafe)]
public class McpShellComponent : IDisposable { ... }
```

机制拆解：

1. **`[ShellComponent]` 特性**：告诉 ReSharper 组件容器"这个类要自动注册并管理"。Shell 级 = 生命周期与整个进程等长，进程启动创建、退出销毁。
2. **容器保证单例**：容器内每个组件定义只实例化一次；其他组件构造函数声明 `McpShellComponent` 参数，注入的都是同一实例。**这是容器实例化管理的结果，不是锁或 static 变量**。
3. **`Lifetime` 管理生命周期**：构造函数接收 `Lifetime`，用 `lifetime.OnTermination(...)` 注册清理回调（停服务器、释放资源）。
4. **`Instantiation.ContainerAsyncAnyThreadSafe`**：实例化模式——按需异步创建、线程安全。解决"组件什么时候、在哪个线程上创建"的问题。

> 对应地，`McpServerComponent` 用 `[SolutionComponent(Instantiation.ContainerAsyncAnyThreadSafe)]` 标记，容器为**每个打开的解决方案**创建一个实例（生命周期 = 解决方案生命周期）。

---

## 5. HTTP 传输层（McpHttpServer）

### 5.1 概览

基于 `HttpListener`，监听 `127.0.0.1:{port}/`。后台线程 `ListenLoop` 用 `GetContext()` 接收连接，丢给线程池处理。`McpHttpServer` 内部持有两个路由表：

- `_solutions`：本地解决方案注册（名称、路径、工具清单、工具处理器）
- `_peers`：Peer 进程注册（解决方案名、路径、端口、工具清单）

### 5.2 HTTP 方法与传输模式

| 方法 | 行为 |
|------|------|
| `POST` | JSON-RPC 请求。通知类请求（无 `id`）返回 202；其余返回 JSON 响应 |
| `GET` | Streamable HTTP 的 SSE 流（保持连接、发送 keepalive，当前不主动推送） |
| `DELETE` | 终止会话（当前不追踪会话状态，仅确认） |
| `OPTIONS` | CORS 预检，返回 204 |

会话校验：客户端带 `Mcp-Session-Id` 头时与服务器 sessionId 比对，不匹配返回 404；所有非 OPTIONS 响应附带 `Mcp-Session-Id` 头。

### 5.3 JSON-RPC 方法

| 方法 | 用途 |
|------|------|
| `initialize` | 协商协议版本：默认 `2025-03-26`，兼容 `2024-11-05` |
| `notifications/initialized` | 客户端初始化完成通知 |
| `tools/list` | 返回工具清单 + `list_solutions` Meta 工具；每个工具 schema 注入可选 `solutionName` 参数 |
| `tools/call` | 调用工具，含解决方案路由与 Peer 代理逻辑 |
| `internal/register` | Peer 注册（内部协议） |
| `internal/deregister` | Peer 注销（内部协议） |
| `internal/status` | 返回端口、角色、已打开的解决方案 |
| `internal/restart` | 重启 HTTP 监听器 |

### 5.4 关键源码：请求处理入口（`McpHttpServer.HandleRequest`）

```csharp
private void HandleRequest(HttpListenerContext context)
{
    // CORS 头（Access-Control-Allow-*）
    // OPTIONS → 204
    // Mcp-Session-Id 校验：不匹配 → 404
    switch (context.Request.HttpMethod)
    {
        case "POST":   HandlePost(context);      break;
        case "GET":    HandleGetSse(context);    break;  // SSE 保活流
        case "DELETE": HandleDeleteSession(context); break;
        default:       405;
    }
}
```

### 5.5 关键源码：Peer 代理转发（`McpHttpServer.ProxyToPeer`）

```csharp
private JsonRpcResponse ProxyToPeer(JsonRpcRequest originalRequest, int peerPort, string toolName, JObject arguments)
{
    // 构造 tools/call 请求 → POST http://127.0.0.1:{peerPort}/
    // 转发前注入 arguments["solutionName"] = 已解析的完整路径（id）：
    //   Peer 端第 1 层精确路径匹配必然命中自己（注册 key 与转发串逐字节同源），本地多解也能正确定位；
    //   Peer 收到后先 Remove 再交给工具处理器，id 不会泄漏到工具；
    //   命中即 IsLocal，结构上不可能再次转发（无递归）。
    // 超时 130s（略大于工具 120s 超时）
    // 返回原样透传的 JsonRpcResponse
}
```

```csharp
catch (WebException ex)
{
    // Peer 不可达 → 移除过期注册
    lock (_lock)
    {
        var staleKey = _peers.FirstOrDefault(p => p.Value.Port == peerPort).Key;
        if (staleKey != null)
        {
            _logger.Warn($"Removing unreachable peer on port {peerPort}");
            _peers.Remove(staleKey);
        }
    }
    return ToolError(originalRequest, "Solution is no longer available (peer unreachable)");
}
```

### 5.6 关键源码：工具调用路由（`McpHttpServer.HandleToolCall`）

```csharp
private JsonRpcResponse HandleToolCall(JsonRpcRequest request)
{
    var toolName = request.Params?["name"]?.ToString();
    var arguments = request.Params?["arguments"] as JObject ?? new JObject();

    if (toolName == "list_solutions")
        return HandleListSolutions(request);

    // 提取并移除 solutionName，之后交给工具处理器
    var solutionName = arguments["solutionName"]?.ToString();
    arguments.Remove("solutionName");

    // 在锁内解析目标解决方案（本地 or Peer），锁外执行
    //   solutionName 匹配：精确名称/路径 → 唯一路径段 → 歧义提示
    //   未传且只有 1 个 → 自动路由；未传且多个 → 报错
    //   本地 → localHandler(arguments)；Peer → ProxyToPeer(...)

    var result = localHandler(arguments);
    var text = result is string s ? s : JsonConvert.SerializeObject(result, Formatting.Indented);
    return new JsonRpcResponse
    {
        Result = new CallToolResult
        {
            Content = { new ContentBlock { Text = text } }
        }
    };
}
```

### 5.7 健康检查与自愈

- 监听循环连续 3 次出错 → `TryRecoverListener()` 就地重建 `HttpListener`。
- 失败后退避 5 秒重试，避免忙等。
- `Restart()` 在后台线程重启监听器（等待旧线程退出，避免两个线程同时 `GetContext`）。
- 主进程 Primary 每 30 秒 `PingServer` 自检，失败触发重启。

---

## 6. PSI 线程调度（McpServerComponent.ExecuteOnPsiThread）

**PSI 操作不能在 .NET 线程池线程上运行**，否则报错 "This action cannot be executed on the .NET TP Worker thread"。所有工具执行都经 `ExecuteOnPsiThread` 调度到 ReSharper 主线程。

### 6.1 关键源码（`McpServerComponent.cs`）

```csharp
private object ExecuteOnPsiThread(IMcpTool tool, JObject args, IShellLocks shellLocks, ISolution solution)
{
    object result = null;
    Exception caught = null;
    var done = new ManualResetEventSlim(false);
    var cancelled = new CancellationTokenSource();

    if (tool is IMcpWriteTool)
    {
        var selfTransacting = tool is IMcpSelfTransactingWriteTool;
        // 写工具：ExecuteOrQueue + 写锁
        shellLocks.ExecuteOrQueue($"ReSharperMcp.{tool.Name}", () =>
        {
            shellLocks.ExecuteWithWriteLock(() =>
            {
                solution.GetPsiServices().Files.CommitAllDocuments();
                if (selfTransacting)
                    result = tool.Execute(args);   // 工具自管事务（如 CodeCleanupRunner）
                else
                    using (PsiTransactionCookie.CreateAutoCommitCookieWithCachesUpdate(
                        solution.GetPsiServices(), $"ReSharperMcp.{tool.Name}"))
                        result = tool.Execute(args);
            });
        });
    }
    else
    {
        // 只读工具：ExecuteOrQueueReadLock
        shellLocks.ExecuteOrQueueReadLock($"ReSharperMcp.{tool.Name}", () =>
        {
            solution.GetPsiServices().Files.CommitAllDocuments();
            result = tool.Execute(args);
        });
    }

    // 阻塞 HTTP 线程等待 R# 线程完成，120s 超时
    if (!done.Wait(TimeSpan.FromSeconds(ToolTimeoutSeconds)))
    {
        cancelled.Cancel();
        throw new TimeoutException($"Timed out after 120s waiting for R# to process '{tool.Name}'.");
    }
    if (caught != null) throw caught;
    return result;
}
```

关键点：

- 用 `ManualResetEventSlim` 阻塞 HTTP 线程，等 R# 线程完成（30s 为参考值，实际 120s）。
- 写工具在 `ExecuteWithWriteLock` + `PsiTransactionCookie` 下执行。
- `IMcpSelfTransactingWriteTool` 跳过分装事务，由工具自管（如 `CodeCleanupRunner`、`BulbActionExecutor`、`RenameRefactoring`）。
- 每次执行前 `CommitAllDocuments()` 提交文档变更，保证 PSI 树新鲜。

---

## 7. 工具系统

### 7.1 接口（`Tools/IMcpTool.cs`）

```csharp
public interface IMcpTool
{
    string Name { get; }
    string Description { get; }
    object InputSchema { get; }
    object Execute(JObject arguments);
}
```

### 7.2 写入工具标记接口（`Tools/IMcpWriteTool.cs`）

```csharp
/// 修改 PSI 树、需要写锁的工具。
public interface IMcpWriteTool : IMcpTool { }

/// 自管事务的写工具：只取写锁，跳过外层 PsiTransactionCookie。
public interface IMcpSelfTransactingWriteTool : IMcpWriteTool { }
```

### 7.3 工具清单

只读工具：

| 工具 | 说明 |
|------|------|
| `find_usages` | 查找符号的所有引用（`IFinder.FindReferences()`） |
| `get_symbol_info` | 符号详情：类型、参数、文档、基类型、声明位置 |
| `find_implementations` | 接口/抽象类实现、虚成员覆盖 |
| `get_file_errors` | PSI 树遍历收集编译错误与未解析引用 |
| `search_symbol` | 按名称子串匹配搜索符号（默认排除命名空间） |
| `go_to_definition` | 定位符号声明 |
| `get_solution_structure` | 列出项目、目标框架、项目间引用 |
| `browse_namespace` | 浏览命名空间层级 |
| `list_symbols_in_file` | 列出文件内所有声明 |
| `fix_usings` | 修复缺失 using（解析未解析的类型引用） |
| `flow` | 方法/类型的控制流摘要（分支、循环、错误路径、调用内联） |
| `get_symbol_source` | 完整声明源码（非截断片段） |
| `get_call_hierarchy` | 调用层级树：incoming / outgoing |
| `get_type_hierarchy` | 继承层级树：supertypes / subtypes |
| `get_diagnostics` | 运行 daemon 检查：严重级别、检查 ID、消息、位置、是否有快速修复 |
| `list_quick_fixes` | 列出位置可用的灯泡操作 |
| `complete_at` | 光标位置的代码补全建议（需要 R# 主线程） |
| `format_file` | CodeCleanupRunner 格式化文件（自管事务写工具） |

写入工具：

| 工具 | 标记接口 | 说明 |
|------|----------|------|
| `rename_symbol` | `IMcpSelfTransactingWriteTool` | 语义级解决方案重命名（`RenameRefactoring`），自管事务以支持 dryRun 回滚 |
| `generate_members` | `IMcpWriteTool` | 生成成员（`GeneratorWorkflowFactory`），依赖框架自动提交事务 |
| `apply_quick_fix` | `IMcpSelfTransactingWriteTool` | 应用灯泡操作（`BulbActionExecutor`，自管事务） |
| `apply_suggestions` | `IMcpSelfTransactingWriteTool` | 按检查 ID 全文件应用快速修复（ReSharper "Fix all in file" 引擎） |

> `complete_at` 逻辑上是只读的，但实现了 `IMcpSelfTransactingWriteTool` **仅为获得主线程调度**（补全引擎断言 R# 主线程），实际不产生写入。

### 7.4 批量模式

多数工具支持批量输入，一次调用处理多个目标：

- 符号类工具（`find_usages`、`get_symbol_info` 等）：`symbols` 数组
- 文件类工具（`get_file_errors` 等）：`filePaths` 数组
- `search_symbol`：`queries` 数组
- `browse_namespace`：`namespaceNames` 数组

结果用 `=== [N/total] label ===` 分隔符拼接。共享选项（`maxResults`、`kinds`、`mode`）在顶层指定，对所有项生效。

---

## 8. 符号解析（PsiHelpers）

### 8.1 文件解析（`ResolveFile`）

四级策略，逐级降级：

1. **精确匹配**（最快，流式不物化）
2. **相对路径**：相对解决方案目录解析
3. **大小写不敏感**（处理 macOS 大小写差异）
4. **后缀匹配**：从路径末尾匹配，要求路径分隔符边界、大小写不敏感

找到项目文件后，`ToSourceFiles()` 取 PSI 源文件；若 PSI 缓存过期返回空（git worktree 常见），则**直接遍历 PSI 模块的 SourceFiles 回退**。

### 8.2 符号名解析（`ResolveSymbolByName`）

支持 `"MyClass"`（短名）、`"Namespace.MyClass"`（限定名）、`"MyClass.InnerType"`（嵌套类型）、成员名、局部函数。用 R# **符号缓存**（`GetSymbolScope(LibrarySymbolScope.NONE, ...)`）做索引查找，而非遍历 PSI 树。三级搜索：

1. **类型/命名空间**（索引快速查找）——跳过 `INamespace` 避免噪声
2. **类型成员**（方法、属性、字段、事件）——限定名 `MyClass.MyMethod` 时搜索包含类型的成员；非限定名时扫描各类型成员（超过 10 个候选即停）
3. **局部函数**（`ILocalFunctionDeclaration` 递归查找）——支持 `BattleLoop.Tick`、`BattleLoop.Update.Tick` 形式

多个匹配时返回**歧义错误**，列出所有候选的限定名、类型、文件、行号。

### 8.3 位置解析（`GetDeclaredElement`）

两阶段：

- **阶段 1（引用）**：向上走 5 层，解析引用（`reference.Resolve()`），找到声明元素——处理"光标指向使用处/类型引用/成员访问"。
- **阶段 2（声明）**：没有引用可解析时，向上走 3 层找最近声明——处理"光标在声明名/关键字/修饰符上"。限 3 层避免跳到过远的祖先声明。

### 8.4 关键源码片段

```csharp
// 位置 → 树节点（行/列 1-based → DocumentCoords 0-based）
var docLine = (Int32<DocLine>)(line - 1);
var docColumn = (Int32<DocColumn>)(column - 1);
var coords = new DocumentCoords(docLine, docColumn);
var offset = document.GetOffsetByCoords(coords);
var treeOffset = psiFile.Translate(new DocumentOffset(document, offset));
return psiFile.FindNodeAt(treeOffset);

// 文件解析（PSI 缓存过期回退）
foreach (var project in solution.GetAllProjects())
    foreach (var module in psiServices.Modules.GetPsiModules(project))
        foreach (var sf in module.SourceFiles)
            if (sf.GetLocation().FullPath == targetPath)
                return ...;
```

---

## 9. SDK 版本要点（2025.3 关键坑）

| 主题 | 正确做法 |
|------|----------|
| `[SolutionComponent]` | 无参构造已废弃，必须 `[SolutionComponent(Instantiation.ContainerAsyncAnyThreadSafe)]`，`Instantiation` 在 `JetBrains.Application.Parts` |
| 线程模型 | PSI 操作必须在 R# 主线程，用 `IShellLocks.ExecuteOrQueueReadLock` / `ExecuteWithWriteLock` 调度 |
| PSI 文件获取 | 没有 `GetPrimaryPsiFile()`，语言无关用 `GetPsiFiles<KnownLanguage>()`，指定语言用 `GetDominantPsiFile<CSharpLanguage>()` |
| 文档坐标 | `DocumentCoords` 要 typed intrinsics：`(Int32<DocLine>)(line - 1)` |
| 文档范围 | `GetDocumentRange()` 是 `TreeNodeExtensions` 扩展，重载解析失败时显式调用 `TreeNodeExtensions.GetDocumentRange(node)` |
| 偏移转坐标 | `IDocument.GetCoordsByOffset()` 已废弃，用 `documentOffset.ToDocumentCoords()` |
| 类型呈现 | 没有 `PresentationLanguageForTests`，用 `element.PresentationLanguage ?? CSharpLanguage.Instance` |
| 抽象检查 | `ITypeElement.IsAbstract()` 不存在，cast 到 `IModifiersOwner` 检查 `IsAbstract` |
| 命名空间 | `GetContainingNamespace()` 在 `ITypeElement` 上，非类型元素经 `GetContainingType().GetContainingNamespace()` |
| 符号范围 | `LibrarySymbolScope` 在 `JetBrains.ReSharper.Psi.Caches` |
| 项目引用 | `GetProjectReferences(tfm)` 需要 `TargetFrameworkId`；`IProjectToProjectReference` 没有 `ResolveReferencedProject()`，用 `GetReferencedName()` |
| Daemon API | `IDaemon` 没有"获取当前高亮"的公开 API，文件错误需走 PSI 树：`IErrorElement`（语法错误）+ `reference.Resolve().ResolveErrorType`（未解析引用） |

---

## 10. 构建与测试

```bash
./install-rider.sh              # 构建 Release、打 JAR、复制到 Rider 插件目录，然后重启 Rider
RESHARPER_MCP_PORT=9999         # 覆盖端口
./publish.sh                    # 打 ZIP 并上传 JetBrains Marketplace（需 JB_MARKETPLACE_PAT）
```

测试（curl）：

```bash
# 握手
curl -s http://127.0.0.1:23741/ -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}'

# 工具列表
curl -s http://127.0.0.1:23741/ -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'

# 调用工具（多解决方案时指定 solutionName）
curl -s http://127.0.0.1:23741/ -X POST -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_symbol","arguments":{"query":"Player","maxResults":10,"solutionName":"MyProject"}}}'
```

版本定义在 `rider-plugin/src/main/resources/META-INF/plugin.xml` 的 `<version>` 与 `<change-notes>`，发布前需更新。

---

## 11. 文件地图

```
src/ReSharperMcp/
  ReSharperMcp.csproj                  # net472，引用 JetBrains.ReSharper.SDK 2025.3.3
  McpShellComponent.cs                 # ShellComponent — 端口绑定/主从/看门狗/Peer 注册
  McpServerComponent.cs                # SolutionComponent — 工具注册 + PSI 线程调度
  McpHttpServer.cs                     # HttpListener HTTP 服务器 + JSON-RPC 路由 + Peer 代理
  PsiHelpers.cs                        # 共享：文件/符号/位置解析、签名、片段截断
  DaemonHighlightingCollector.cs       # 运行 daemon 收集高亮（诊断/快速修复工具用）
  Protocol/
    JsonRpc.cs                         # JSON-RPC 请求/响应/错误类型
    McpTypes.cs                        # MCP 类型：InitializeResult、ToolDefinition 等
  Tools/
    IMcpTool.cs                        # 工具接口：Name/Description/InputSchema/Execute
    IMcpWriteTool.cs                   # 写工具标记接口（写锁 + PsiTransaction）
    FindUsagesTool.cs                  # find_usages
    GetSymbolInfoTool.cs               # get_symbol_info
    FindImplementationsTool.cs         # find_implementations
    GetFileErrorsTool.cs               # get_file_errors
    SearchSymbolTool.cs                # search_symbol
    GoToDefinitionTool.cs              # go_to_definition
    GetSolutionStructureTool.cs        # get_solution_structure
    BrowseNamespaceTool.cs             # browse_namespace
    ListSymbolsInFileTool.cs           # list_symbols_in_file
    FixUsingsTool.cs                   # fix_usings
    FlowTool.cs                        # flow
    FormatFileTool.cs                  # format_file
    GetSymbolSourceTool.cs             # get_symbol_source
    GetCallHierarchyTool.cs            # get_call_hierarchy
    GetTypeHierarchyTool.cs            # get_type_hierarchy
    GetDiagnosticsTool.cs              # get_diagnostics
    ListQuickFixesTool.cs              # list_quick_fixes
    CompleteAtTool.cs                  # complete_at
    RenameSymbolTool.cs                # rename_symbol
    GenerateMembersTool.cs             # generate_members
    ApplyQuickFixTool.cs               # apply_quick_fix
    ApplySuggestionsTool.cs            # apply_suggestions

rider-plugin/META-INF/plugin.xml       # IntelliJ 插件描述符（后端插件无需 Java/Kotlin）
```

---

## 12. 扩展指南（新增工具）

给仓库加一个新工具，需要三步：

1. **新建工具类**：实现 `IMcpTool`（只读）或 `IMcpWriteTool` / `IMcpSelfTransactingWriteTool`（写入）。参考 `FindUsagesTool` 的模式——构造函数接收 `ISolution`，`Execute` 里用 `PsiHelpers` 解析参数。
2. **注册工具**：在 `McpServerComponent` 构造函数里调用 `RegisterTool(new YourTool(solution), shellLocks, solution, tools, handlers)`。
3. **更新文档**：CLAUDE.md 的工具表 + 本文档 §7.3。

写入工具注意：`Execute` 里**不要**自己开事务（`IMcpWriteTool`）或自行管理事务（`IMcpSelfTransactingWriteTool`），调度逻辑在 `ExecuteOnPsiThread` 统一处理。只读工具通过 `ExecuteOrQueueReadLock` 执行，写入工具通过 `ExecuteOrQueue` + 写锁执行。
