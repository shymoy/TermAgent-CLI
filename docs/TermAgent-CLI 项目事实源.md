# TermAgent-CLI 项目事实源

> 用途：把本文作为 ChatGPT、面试回答、代码评审或新成员理解项目时的第一份上下文。
> 本文只记录当前仓库能够由源码、构建文件或测试直接证明的事实；类已经存在但主入口尚未接线的能力会明确标记。

## 0. 快照与使用规则

- 项目：`TermAgent-CLI`
- 版本常量：`TermAgent-CLI v0.1.1-learning`
- 核验日期：2026-07-15（Asia/Shanghai）
- 核验提交：`c0c6028d66b7f11a6833eca410ea04a6cdb6fa4a`
- Java 生产代码规模：约 23,300 行
- Java 测试代码规模：约 2,445 行，106 个 `@Test`
- 语言与构建：Java 21、Gradle Wrapper、Kotlin DSL

给 ChatGPT 使用时，应遵守以下优先级：

1. 用户提供的最新代码或 diff。
2. 当前仓库源码与测试。
3. 本文对应的提交快照。
4. `README.md`、注释和类名所表达的设计意图。

若本文与更新后的代码冲突，以代码为准。不能仅因为某个类存在，就宣称对应能力已在 TUI、Print 或 Remote 主流程中生效。

推荐附在问题前的提示：

```text
下面是 TermAgent-CLI 在提交 c0c6028 上的项目事实源。请只依据事实源和我随后提供的代码回答；
区分“当前已接入主流程”“底层已有实现但未接线”“合理推断”。引用具体类或方法，不要虚构统一抽象层。
```

## 1. 一句话定义

TermAgent-CLI 是一个用 Java 21 实现的终端 AI 编程代理运行时：它把不同模型协议统一成流式事件，把模型产生的工具调用交给带权限、Hook、并发调度和取消能力的执行链，并提供 TUI、一次性命令和远程 Web 三种交互入口。

它不是简单的聊天壳。当前仓库还实现了会话恢复、两层上下文控制、长期记忆、技能、MCP、子 Agent、团队、任务列表、Git worktree、文件回滚和 OS 级 Bash 沙箱等模块。

## 2. 技术栈与依赖

| 领域 | 当前实现 |
|---|---|
| 语言 | Java 21，使用 record、sealed interface、模式匹配 switch、虚拟线程 |
| 构建 | Gradle Wrapper，`application` 与 Shadow Jar |
| 终端 | JLine 3.28.0；项目自带 Elm/Bubble Tea 风格的 `tui.tea` 事件循环 |
| Markdown | Mordant 3.0.2 |
| 模型 SDK | Anthropic Java 2.34.0、OpenAI Java 4.37.0 |
| 通用 HTTP | JDK `HttpClient`，用于 OpenAI-compatible SSE 等 |
| MCP | MCP Java SDK 1.1.3 |
| Remote | Javalin 6.6.0，HTTP + WebSocket |
| 配置 | SnakeYAML 2.2 |
| JSON | Jackson Databind 2.21.3 |
| 测试 | JUnit Jupiter 5.11.4 |

构建入口为 `io.github.shymoy.termagent.TermAgentCli`。`shadowJar` 生成无版本后缀、包含依赖的 `build/libs/TermAgent-CLI.jar`。

## 3. 目录与模块职责

生产代码根包是 `io.github.shymoy.termagent`：

| 包 | 职责 |
|---|---|
| `agent` | Agent 主循环、统一上层事件、取消句柄、工具执行调度 |
| `llm` | Anthropic、OpenAI Responses、OpenAI-compatible、DeepSeek 协议适配 |
| `conversation` | 协议无关的消息、thinking、tool use/result 历史 |
| `tool` / `tool.impl` | 工具接口、注册表和本地文件/命令工具 |
| `toolresult` | 大工具结果的稳定裁剪、卸载与持久化记录 |
| `compact` | 上下文用量估算、摘要压缩和压缩后恢复附件 |
| `config` | YAML 映射、分层合并、路径兼容与校验 |
| `permission` / `sandbox` | 应用层授权决策与 OS 级 Bash 隔离 |
| `prompt` | 系统提示词分段、排序和环境快照 |
| `memory` | 指令加载、长期记忆、相关记忆召回 |
| `session` / `history` / `filehistory` | 会话日志、输入历史、文件备份和回滚 |
| `skill` / `command` | Skill 与斜杠命令的解析和注册基础设施 |
| `mcp` | MCP 客户端连接和远程工具适配 |
| `subagent` | 一次性、后台和隔离子 Agent |
| `teams` | 持久团队、邮箱、终端后端和协调器工具过滤 |
| `task` | 持久任务列表及 Task 工具 |
| `worktree` | Git worktree 生命周期与会话状态 |
| `tui` / `tui.tea` | 默认终端 UI、状态机、渲染与事件循环 |
| `print` | `-p` 非交互执行与 JSONL 输出 |
| `remote` | HTTP 页面、WebSocket 控制协议和 Agent 事件桥接 |

## 4. 启动方式与三种运行入口

`TermAgentCli.main` 手工解析参数，然后加载配置并三选一：

### 4.1 默认 TUI

无 `-p`、无 `--remote` 时创建 `TermAgentModel` 和 `Program`。用户先选择 provider，再由 `TermAgentModel.initializeProvider()` 组装 client、registry、Agent、权限、MCP、技能、会话和文件历史。

TUI 的核心是 `Model.update(message) -> UpdateResult` 状态机。Agent 在虚拟线程中运行，UI 通过有界事件队列轮询结果，因此模型请求和工具执行不会直接阻塞按键处理。Ctrl-C 通过 `AgentRunHandle`/`CancellationToken` 协作取消当前 Run，而不是默认退出整个 TUI。

### 4.2 Print 模式

`-p "prompt"` 调用 `PrintMode.run`，固定选择 providers 列表的第一项：

- `text`：只向 stdout 输出最终一轮文本；工具中间轮文本会在 `TurnComplete` 时清掉。
- `stream-json`：逐行输出 `tool_use`、`tool_result`、`usage`、`error`，最后输出一个 `result` 对象；文本 token 增量不逐条输出。
- 权限模式固定为 `BYPASS`，但仍经过 `PermissionChecker` 更高优先级的危险命令和受保护路径硬拒绝。
- `AskUserQuestion` 在非交互环境中自动得到空答案。
- 事件等待超时为 120 秒。

### 4.3 Remote 模式

`--remote` 默认监听 `:18888`；`--remote=...` 可改变端口字符串。`RemoteServer` 固定选择第一项 provider，启动：

- `GET /`：返回打包在资源中的单页 Web UI。
- `WS /ws`：收发聊天、工具、权限、AskUser、usage、compact、retry、恢复会话等事件。
- 服务实际调用 `start("0.0.0.0", port)`；当前地址解析最终只取端口，因此并不会按参数中的 host 限制绑定地址。
- 当前没有看到 HTTP 鉴权或 WebSocket 鉴权；把端口暴露到非可信网络需要额外保护。
- Remote 的 `/rewind` 明确返回“尚不支持”。

## 5. 核心 Agent 运行链路

权威入口是 `Agent.agentLoop(ConversationManager, BlockingQueue<AgentEvent>, CancellationToken)`。

一次完整 Run 的真实过程如下：

1. `Agent.startRun` 在 Java 虚拟线程中启动主循环，并持有取消 token。
2. 长期指令和记忆通过 `ConversationManager.injectLongTermMemory` 在整个会话只注入一次。它们在内部表现为带 `<system-reminder>` 的 user 消息，不是内部 `system` role。
3. 每轮开始拉取团队邮箱和后台任务通知，并注入 reminder。
4. 从 `ToolRegistry.getAllSchemas(protocol)` 固定本轮工具 schema；团队存在时再应用协调器工具白名单。
5. 先用 `ToolResultBudget` 对 API 视图中的超大历史工具结果做稳定替换，再让 `ContextCompactor` 判断是否需要二层摘要。
6. 把尚未发现的 deferred 工具名称作为 reminder 告诉模型；完整 schema 仍不发送。
7. Plan 模式下补充计划文件路径和 `ExitPlanMode` 流程提示。
8. 调用 `LlmClient.stream(apiConversation, tools)`。供应商客户端把自身协议转换成统一 `StreamEvent`。
9. Agent 消费文本、thinking、工具调用和 usage：增量立即转换成 `AgentEvent` 交给 UI，同时在本轮内累积完整内容。
10. 正常流结束后，才把 assistant 文本、thinking block 和 tool-use block 一次性提交到内部会话。
11. 若没有工具调用，发送 `LoopComplete`，Run 结束。
12. 若有工具调用，`StreamingExecutor` 执行全部调用，把结果作为内部 user 消息中的 `ToolResultBlock` 写回，再进入下一轮 LLM 请求。

这条链可以概括为：

```text
用户输入
  -> ConversationManager
  -> ToolResultBudget / ContextCompactor
  -> LlmClient 协议适配
  -> StreamEvent
  -> Agent 聚合
  -> StreamingExecutor
  -> ToolRegistry -> Tool.execute
  -> ToolResultBlock 写回 ConversationManager
  -> 下一轮，直到无工具调用
```

### 5.1 错误与恢复

- 单个流事件等待超过 30 秒，Agent 报 `Stream timeout` 并结束。
- 上下文过长最多触发 3 次强制压缩重试。
- 错误文本包含 `rate limit` 时固定等待 5 秒后重试；代码没有指数退避或全局重试上限。
- `stopReason == max_tokens` 时先把最大输出提高到 64,000，再最多续写 3 次。
- `maxIterations > 0` 才限制迭代次数；三个主入口当前都没有设置该值，因此默认没有显式迭代上限。
- Run 取消后，迟到的模型响应或工具结果在提交会话前会再次检查 token，避免污染历史。

## 6. 内部消息与事件模型

### 6.1 内部会话

`ConversationManager` 保存项目自己的 `Message`，不直接保存 SDK message。主要 role 是 `user` 和 `assistant`：

- assistant 消息可同时带文本、`ThinkingBlock` 和 `ToolUseBlock`。
- 工具结果在内部保存成 role=`user` 的空文本消息 + `ToolResultBlock`。
- Anthropic 适配器把它转成 user message 内的 `tool_result` block。
- OpenAI-compatible 适配器把它转成独立的 role=`tool` 消息。
- `getMessages()` 返回只读副本；压缩器通过 `getMessagesMutable()` 重写历史。
- 该类本身没有并发写保护，正确使用依赖上层对单会话写入的串行控制。

### 6.2 两层事件

`StreamEvent` 是 LLM client 到 Agent 的协议无关事件：文本、thinking、工具调用开始/参数增量/完成、流结束、错误。

`AgentEvent` 是 Agent 到 TUI/Print/Remote 的上层事件：正文、thinking、工具使用/结果、turn/loop 完成、usage、错误、压缩、重试、权限请求和 AskUser 请求。

权限与 AskUser 事件携带 `CompletableFuture`，上层 UI 填回结果，工具执行线程同步等待。权限请求最长等待 5 分钟。

## 7. 模型供应商与协议边界

`LlmClient.create` 按 `ProviderConfig.protocol` 选择实现：

| protocol | 实现 | 主要 API 形态 |
|---|---|---|
| `anthropic` | `AnthropicClient` | Anthropic Messages 流式 API |
| `openai` | `OpenAiClient` | OpenAI Responses 流式 API |
| `openai-compat` | `OpenAiCompatClient` | `/chat/completions` SSE |
| `deepseek` | `DeepSeekClient` | OpenAI-compatible 子类，附加 DeepSeek 语义 |

边界原则是：每个客户端负责请求序列化、历史角色转换、工具 schema 转换、流解析和错误分类；Agent 不知道 SDK 原始事件。

### 7.1 Anthropic

- 支持文本、thinking、thinking signature、tool use/result 和 usage。
- 为 system、tools 和最后一条 user 消息尾部设置 Prompt Cache 锚点。
- 会合并连续同角色消息以满足协议约束。
- 构造 client 时尽力从模型端点取得上下文窗口并回填 `ProviderConfig`，失败静默回退。

### 7.2 OpenAI

- 使用 Responses API。
- thinking 作为 reasoning item 回传，工具结果通过 call id 对齐。
- `thinking=true` 时设置 reasoning effort；缓存 token 从 usage 明细取得。

### 7.3 OpenAI-compatible

- 直接使用 JDK HTTP client 发起流式 chat completions。
- 支持标准 `content`、`tool_calls`、usage，也兼容非标准 `reasoning_content`。
- 对多个 tool call 按 index 分别累积 name、id 和 JSON 参数。

### 7.4 DeepSeek

- 是 `OpenAiCompatClient` 的专用子类，不在共享客户端中堆 provider 条件。
- 配置默认 base URL 为 `https://api.deepseek.com`，默认 model 为 `deepseek-v4-pro`。
- 旧别名和 `deepseek-chat`/`deepseek-reasoner` 会经过模型名归一化。
- thinking 模式可注入 `reasoning_effort`，并回传 `reasoning_content`。

### 7.5 上下文和输出上限

`ProviderConfig.resolvedContextWindow()` 的优先级：

1. 配置中的正整数 `context_window`。
2. provider 自动取得并缓存的窗口，目前代码说明只在 Anthropic client 走这条路径。
3. 内置模型名子字符串表。
4. Claude 默认 200k，其他默认 128k。

内置表包含 GPT-4.1/1M、GPT-4o/128k、OpenAI reasoning/200k、DeepSeek V4/1M、Claude/200k 等值。这些只是代码内默认值，不应被描述为供应商的永久规格。

`resolvedMaxOutputTokens()`：显式配置优先；否则 thinking 模式 64k，普通模式 8192。

## 8. 工具体系

### 8.1 工具契约

所有工具实现 `Tool`：

- `name()`：模型调用和注册表查找使用的稳定名称。
- `description()` 与 `schema()`：发送给模型的描述和 JSON Schema。
- `category()`：`READ`、`WRITE` 或 `COMMAND`，参与并发和权限决策。
- `execute(args)` / `execute(args, context)`：执行入口；新入口可感知取消。
- `shouldDefer()`：是否延迟暴露完整 schema。

`ToolRegistry` 用并发 Map 保存工具，同名注册会覆盖旧实例。Anthropic 使用内部 `input_schema`；OpenAI 和 OpenAI-compatible 在注册表层被转换为 `type=function + parameters`。

### 8.2 主入口注册的工具

| 工具 | 类别 | 默认延迟 | 作用 |
|---|---:|---:|---|
| `ReadFile` | READ | 否 | 带行号读取文件，并更新文件状态缓存 |
| `Glob` | READ | 否 | 查找文件 |
| `Grep` | READ | 否 | 搜索内容 |
| `WriteFile` | WRITE | 否 | 写文件，接入文件历史和状态缓存 |
| `EditFile` | WRITE | 否 | 精确文本替换，接入文件历史和状态缓存 |
| `Bash` | COMMAND | 否 | 执行命令，可注入 OS 沙箱 |
| `ToolSearch` | READ | 否 | 搜索或 select deferred 工具，并标记为已发现 |
| `AskUserQuestion` | READ | 是 | 通过上层事件向用户提问 |
| `ExitPlanMode` | READ | 否 | 完成计划审批流程 |
| `Agent` | COMMAND | 否 | 启动子 Agent 或团队成员 |
| `EnterWorktree` / `ExitWorktree` | COMMAND | 是 | 进入或退出 worktree 会话 |
| `TaskCreate/Get/List/Update` | COMMAND | 是 | 操作持久任务列表 |
| `TeamCreate/Delete`、`SendMessage` | COMMAND | 否 | 团队生命周期与邮箱通信 |
| `mcp__<server>__<tool>` | COMMAND | 是 | 调用 MCP 服务端工具 |

`StreamingExecutor` 会把相邻 READ 工具组成并行批次，用每任务一个虚拟线程执行；WRITE 和 COMMAND 各自形成串行批次。最终结果顺序保持与模型调用顺序一致。

单个工具显示/回传输出在执行器层最多 10,000 字符。更早历史中的大结果还会经过单结果 50,000 字符、单消息聚合 200,000 字符的 `ToolResultBudget` 处理。

### 8.3 工具执行的固定顺序

`StreamingExecutor.executeSingle` 的顺序是：

1. 注册表查找；未知工具返回错误。
2. 取消检查。
3. `PermissionChecker.check`。
4. ASK 时发送权限事件并等待用户；DENY 时直接返回工具错误。
5. `pre_tool_use` Hook；Hook 可拒绝。
6. `Tool.execute(args, context)`。
7. 对成功的 ReadFile 保存压缩恢复快照。
8. 截断展示输出并发送 `ToolResultEvent`。
9. `post_tool_use` Hook。
10. Agent 把结果写回会话。

## 9. MCP 接入

`McpManager` 支持两种 transport：

- 配置 `command` 时使用本地 stdio，可带 args 和 env。
- 否则配置 `url` 时使用 Streamable HTTP，可带 headers。
- env/header 值支持 `${ENV_NAME}` 替换；变量未定义时保留原占位符。
- 每个请求超时 60 秒。

连接过程是：读取 `mcp_servers` → 为每个配置创建同步 client → initialize → 读取 server instructions → listTools → 包装成内部 `Tool` → 注册进同一个 `ToolRegistry`。

包装后的名称为 `mcp__<清洗后的 server>__<清洗后的 tool>`，非字母数字下划线字符会变成下划线。MCP 工具统一归类为 COMMAND 并延迟暴露；模型先用 `ToolSearch` 的 `select:<name>` 发现后，下一轮才收到完整 schema。调用仍经过普通权限、Hook 和执行器链，不需要修改 Agent loop。

当前 MCP wrapper 只从服务端结果提取文本内容；图片等非文本 MCP content 不会转入 `ToolResult`。单个 MCP server 失败不会阻止其余 server 初始化。

## 10. 权限与沙箱

权限和沙箱是两层不同机制：

- `PermissionChecker` 决定某次工具调用是 ALLOW、DENY 还是 ASK。
- `SeatbeltSandbox`/`BwrapSandbox` 限制 Bash 进程实际能读写或联网的范围。

### 10.1 权限模式

| 模式 | READ | WRITE | COMMAND |
|---|---:|---:|---:|
| DEFAULT | 允许 | 询问 | 询问 |
| ACCEPT_EDITS | 允许 | 允许 | 询问 |
| PLAN | 默认矩阵；另有规划工具特例 | 默认矩阵 | 默认矩阵 |
| BYPASS | 允许 | 允许 | 允许 |

模式只是最后兜底。实际检查优先级是：Plan 特例 → 安全命令白名单 → 危险命令硬拒绝 → 受保护写路径硬拒绝 → 项目外路径询问 → YAML 规则 → 会话“始终允许” → 沙箱自动放行 COMMAND → 模式矩阵。

因此 BYPASS 也不能绕过危险命令和受保护路径。默认保护 `.termagent`/`.mewcode` 下的配置、local 权限和 skills 路径。

权限规则按用户、项目、项目 local 分层加载；检查时后加载、后出现的匹配规则优先。规则形如 `Bash(git *)` 或 `WriteFile(/etc/*)`。

### 10.2 OS 沙箱

- macOS：若 `/usr/bin/sandbox-exec` 存在，使用 Seatbelt profile。
- Linux：若 PATH 中存在 `bwrap`，使用 bubblewrap。
- 其他平台：无实现。
- TUI 默认允许写项目目录和 `/tmp`，保护权限系统给出的 deny-write 路径。
- TUI 可以切换“沙箱 + 自动放行”“沙箱 + 常规权限”“关闭沙箱”。
- Print 和 Remote 当前没有像 TUI 一样把 sandbox 实例注入 BashTool。

## 11. 上下文控制、会话和文件恢复

### 11.1 工具结果预算

`ToolResultBudget` 为 API 请求创建替换后的临时会话，不直接破坏原始 `ConversationManager`。替换决定记录在 `ContentReplacementState`，并尽力写入 `.termagent/session`，保证同一历史在后续轮保持相同字节前缀，尤其服务于 Anthropic Prompt Cache。

当前 Agent 传给 `ToolResultBudget` 的目录是 `.termagent/session`，因此超大内容会卸载到 `.termagent/session/tool_results/`，替换决策追加到 `.termagent/session/replacement_records.jsonl`；API 历史只保留稳定标记和预览。`ContextCompactor` 内还保留一个写 `.termagent/tool_results/` 的旧卸载 helper，但当前 `manage()` 明确把第一层交给 `ToolResultBudget`，主循环没有调用该 helper。

### 11.2 二层摘要压缩

`ContextCompactor` 以 provider 的真实 usage 为锚点；锚点后的新消息再用约 3.5 字符/token 估算。冷启动没有 usage 时才估算全部历史。

触发公式不是简单的 80%：

```text
effectiveWindow = contextWindow - min(maxOutput, 20,000)
软触发阈值 = effectiveWindow - 13,000
硬触发阈值 = effectiveWindow - 3,000
```

二层压缩只摘要较早前缀，原样保留最近尾部：至少考虑保留 5 条消息，目标最近 10k tokens，上限 40k tokens。摘要后还可附加最近读取文件和已加载 skill 的恢复信息。连续自动压缩失败 3 次后软触发熔断；硬触发仍可强制执行。

### 11.3 会话持久化

`SessionManager` 使用按行 JSON 会话日志，支持普通消息、tool-use id 和 `compact_boundary`。压缩边界保存摘要与保留尾部，恢复时从最后一个边界重建“摘要 + keep + 新消息”。

- 新 session 使用随机 ID。
- 启动时清理超过 30 天的 session。
- TUI 和 Remote 支持恢复；Print 不提供恢复交互。
- TUI 正常终止轮会把最终 assistant 文本写盘。

### 11.4 文件历史与 rewind

`FileHistory` 在 Edit/Write 前跟踪备份，在对话关键点创建最多 100 个 snapshot。TUI `/rewind` 可选择恢复文件、对话或两者。Remote 明确未实现 rewind UI。

`FileStateCache` 让 ReadFile、EditFile、WriteFile 形成乐观一致性闭环：读取记录文件状态，编辑/写入校验并刷新状态，降低基于过期内容覆盖用户改动的风险。

## 12. 指令、记忆、技能与命令

### 12.1 指令文件

`InstructionLoader` 支持：

- 用户级 `~/.termagent/TERMAGENT.md` 与 `AGENTS.md`。
- 从 Git root 到工作目录逐层的 `TERMAGENT.md` 与 `AGENTS.md`。
- `TERMAGENT.local.md` 私有覆盖。
- 旧版 `MEWCODE.md`、`MEWCODE.local.md`、`.mewcode/INSTRUCTIONS.md` 兼容。
- `@include` 递归展开，最大深度 5。

这些内容最终由 Agent 作为一次性 `<system-reminder>` 注入内部会话。

### 12.2 自动记忆

`MemoryManager` 区分用户级 `~/.termagent/memory/` 和项目级 `.termagent/memory/`。user/feedback 类型归用户级，project/reference 类型归项目级。每 5 次完成轮次才满足自动抽取间隔条件。

TUI 在首次消息前注入 memory 入口内容，并可异步预取相关记忆；相关记忆在第一轮工具执行后、下一轮前以 reminder 注入。Print 只注入 instructions，没有把构建出的 memory section 设置给 Agent；Remote 的 `memoryContent` 字段在当前初始化路径中保持空值。

### 12.3 Skill

`SkillCatalog` 能解析两种目录格式：`skill.yaml + prompt.md`，或带可选 YAML frontmatter 的 `SKILL.md`。元数据包含 allowed tools、mode、model 和 fork context。`SkillExecutor` 也已实现 inline/fork、参数替换和工具白名单校验。

但当前三个主入口实际使用的是 `new SkillCatalog()` + 仅加载项目的可读 skills 目录，并把每个 skill 注册成 PROMPT 斜杠命令；它们没有调用完整的 `SkillCatalog.loadCatalog()` 三层加载，也没有调用 `SkillExecutor`。因此当前主流程中的事实是：

- 项目 `.termagent/skills/<name>/...` 可以成为 `/<name>` prompt 命令。
- resources 中的 built-in skills 和用户全局 skills 虽有加载实现，当前主入口没有接线。
- allowed_tools、fork mode、model 等高级 skill 执行语义当前不会由主入口强制执行。

### 12.4 斜杠命令

`CommandRegistry` 内置 14 个命令：`help`、`mcp`、`clear`、`compact`、`status`、`memory`、`plan`、`session`、`permission`、`resume`、`rewind`、`skills`、`review`、`sandbox`，部分带别名。

`CommandLoader` 已能从用户和项目 `.termagent/commands/**/*.md` 解析自定义 PROMPT 命令，但 TUI、Print、Remote 当前均未调用它。因此不能把“支持自定义命令文件”描述为当前主入口已生效能力。

## 13. 子 Agent、任务、团队与 worktree

### 13.1 子 Agent

`Agent` 工具支持三条路径：

- 指定 `subagent_type`：同步或后台运行 general-purpose、plan、explore。
- 指定 `team_name`：作为长期团队成员启动。
- 未指定 `subagent_type`：尝试从父会话 fork。

子 Agent 使用独立 `ConversationManager`，并通过 `ToolFilter` 获得与 spec 对应的工具子集。后台任务由 `SubAgentTaskManager` 维护 PENDING/RUNNING/COMPLETED/FAILED/CANCELLED 状态，完成通知在主 Agent 下一轮前注入。

当前主入口没有调用 `AgentLoader.loadAll`/`setAgentSpecs`，所以自定义 `.termagent/agents/*.md` 定义没有接线；使用内置的 general-purpose、plan、explore fallback。

当前主入口也没有给 `AgentTool` 设置 `parentConversation`，因此省略 `subagent_type` 会进入 fork 路径并得到 `fork requires parent conversation context`。可靠调用应显式指定 subagent type。

`model` override schema 提供 sonnet/opus/haiku，但主入口没有设置 `modelResolver`；当前会退回父 client，不能宣称它一定切换模型。

### 13.2 持久任务

`TaskList` 把任务保存到 `.termagent/tasks/<listId>.json`，工具包括 Create、Get、List、Update。任务支持状态、依赖关系、负责人和元数据，并对更新做同步持久化。Task 工具默认 deferred。

### 13.3 团队

团队与普通子 Agent 不同：成员是长运行 worker，通过文件邮箱互发消息，可使用 in-process、tmux 或 iTerm 后端。主 Agent 一旦存在团队，就通过 `Coordinator` 白名单限制自己只保留协调类工具，避免继续直接修改文件。

协调器允许的工具集合包括 Agent、SendMessage、Task 系列、TeamCreate/Delete、ReadFile/Glob/Grep/Bash。

### 13.4 Git worktree

`WorktreeManager` 在 `.termagent/worktrees/<branch>` 管理 worktree，支持创建、移除、列出、陈旧清理和变更检测；默认陈旧阈值是 720 小时。子 Agent 可请求 `isolation=worktree`。

TUI 初始化时恢复遗留的 worktree session。不过 TUI 注册 `EnterWorktreeTool` 时发生在新 `sessionId` 生成之前，传入值为 null；Print 与 Remote 的顺序正确。这个差异属于当前源码事实，回答 TUI worktree 会话绑定问题时应特别核验。

## 14. Hook 系统

Hook 事件枚举包括：session_start/end、turn_start/end、pre_send、post_receive、pre_tool_use、post_tool_use、shutdown。

动作类型包括：command、prompt、http、agent；单条 Hook 还支持 condition、reject、once、async、on_error 和 timeout。command 默认超时 10 分钟，HTTP 默认 10 秒。command Hook 注入新的 `TERMAGENT_EVENT/TOOL/FILE_PATH` 环境变量，同时保留 `MEWCODE_*` 兼容变量。

需要区分“引擎支持”和“入口实际触发”：工具执行链明确触发 pre/post_tool_use；TUI 还明确触发 session_start、turn_start、turn_end。不能只根据 EventName 枚举就断言所有入口已在所有时机触发全部事件。

## 15. 配置事实

未显式传配置路径时，加载顺序为：

1. `~/.mewcode/config.yaml`
2. `~/.termagent/config.yaml`
3. `<项目>/.mewcode/config.yaml`
4. `<项目>/.termagent/config.yaml`
5. `<项目>/.mewcode/config.local.yaml`
6. `<项目>/.termagent/config.local.yaml`

后加载覆盖前加载。providers 整体替换；MCP 按 name 合并/覆盖；hooks 追加；permission mode 覆盖。显式路径或 `TERMAGENT_CONFIG`/旧 `MEWCODE_CONFIG` 则只加载单文件，不做上述分层合并。

最小 provider 示例：

```yaml
providers:
  - name: deepseek
    protocol: deepseek
    api_key: ${DEEPSEEK_API_KEY} # 注意：普通 provider 字段本身不会做 ${...} 展开
    thinking: true
    reasoning_effort: high

mcp_servers:
  - name: filesystem
    command: npx
    args: ["-y", "@modelcontextprotocol/server-filesystem", "."]
    env:
      TOKEN: "${MCP_TOKEN}"      # MCP env/header 字段会做环境变量展开
```

更准确的 API key 用法是省略 `api_key`，让 `resolvedApiKey()` 按协议读取 `OPENAI_API_KEY`、`ANTHROPIC_API_KEY` 或 `DEEPSEEK_API_KEY`。配置中的普通 `api_key: ${...}` 不会由 `ConfigLoader` 展开。

重要接线边界：`AppConfig.permissionMode` 和 `AppConfig.sandbox` 虽能解析和合并，但 `TermAgentCli` 构造 TUI/Remote 时只传 providers、MCP、hooks，Print 也自行使用 BYPASS；当前主入口没有消费这两个字段。因此不能宣称 YAML 中的 permission_mode/sandbox 已控制运行时。

`ConfigLoader.validate` 当前直接遍历 `cfg.getProviders()`；若 providers 缺失，可能产生空指针而不是友好的 `ConfigException`。

## 16. 数据目录与旧版兼容

新数据统一写入 `.termagent`。`AppPaths` 在读取时优先新路径，不存在才回退 `.mewcode`；需要写旧文件时按单文件提升到新目录，避免复制大型 worktree。

主要数据位置：

| 数据 | 位置 |
|---|---|
| 配置 | `~/.termagent/config.yaml`、项目 `.termagent/config*.yaml` |
| 权限 | 用户/项目 `.termagent/permissions*.yaml` |
| 指令 | `TERMAGENT.md`、`AGENTS.md`、local/legacy 变体 |
| 记忆 | 用户和项目 `.termagent/memory/` |
| 技能 | `.termagent/skills/`（当前入口实际只接项目级） |
| 会话 | 项目 `.termagent/sessions/<session-id>.jsonl` |
| 工具结果替换记录 | 项目 `.termagent/session/replacement_records.jsonl` |
| 工具大结果 | 项目 `.termagent/session/tool_results/`（当前主循环路径） |
| 任务 | 项目 `.termagent/tasks/` |
| worktree | 项目 `.termagent/worktrees/` |
| 计划 | 项目 `.termagent/plans/` |
| 文件历史 | 由 `FileHistory` 在项目数据目录下按 session 管理 |

不得提交 API key 或本地 `.termagent` 状态。

## 17. 测试覆盖与验证边界

当前测试集中覆盖：

- Agent 运行、取消和事件完成语义。
- ContextCompactor 阈值、摘要与恢复附件。
- tool-result budget、替换状态与持久化。
- Provider context window、DeepSeek 请求和流式历史回传。
- Anthropic/OpenAI-compatible usage 解析。
- AppPaths 兼容迁移、InstructionLoader、SessionManager。
- HookEngine、ToolSearch、文件邮箱、TUI tea Program。

测试目录没有与所有生产包一一对应。尤其 Remote 的网络协议、MCP 真实 server 集成、沙箱真实隔离、团队终端后端和完整 TUI 交互没有同等强度的端到端测试证据。描述这些模块时应说“源码实现了”，不要仅凭完整测试通过就说“所有平台和外部服务均已验证”。

## 18. 当前已确认的接线缺口与风险清单

以下不是推测，而是从“有实现类”与“三个主入口实际调用”对照得出的当前事实：

1. `permission_mode`、`sandbox` YAML 字段未进入三种运行入口的运行时装配。
2. `CommandLoader` 未被主入口调用，自定义 commands 文件不生效。
3. 完整三层 `SkillCatalog.loadCatalog` 和 `SkillExecutor` 未被主入口使用；built-in/user skills 与高级 skill 语义未接线。
4. `AgentLoader` 未被调用，自定义 agent 定义未接线。
5. `AgentTool.parentConversation` 未设置，不带 `subagent_type` 的 fork 路径会失败。
6. `AgentTool.modelResolver` 未设置，子 Agent model override 不保证切换 client。
7. Print 和 Remote 没有装配 OS sandbox；Remote 命令上下文也固定报告 sandbox 不可用。
8. Remote 绑定 `0.0.0.0` 且没有源码可见鉴权，默认不适合直接暴露公网。
9. Remote 的 cancel 中断消费线程，但没有像 TUI 一样持有并取消 `AgentRunHandle`；对底层 Run 的停止保证弱于 TUI。
10. TUI 创建 EnterWorktreeTool 时 sessionId 尚未生成。
11. Config 缺 providers 时校验可能 NPE。
12. README 的能力列表描述模块存在，但不等同于每个模块在所有运行模式均完整接线。

这些条目适合用作后续工程改进清单，但本文不把它们自动等同为用户已经要求修复的 bug。

## 19. 如何扩展项目

### 19.1 新增普通工具

1. 在职责匹配的包实现 `Tool`。
2. 给出稳定 name、description、基础 `input_schema`、category 和 execute。
3. 决定是否 deferred。
4. 在 TUI、Print、Remote 共用的装配路径注册；当前项目没有抽出统一 bootstrap，因此要核对三个入口。
5. 若是文件写工具，接入 FileHistory/FileStateCache；若要取消，覆盖带 `ToolExecutionContext` 的 execute。
6. 添加 schema、权限、执行和回归测试。

无需修改 Agent loop；注册后 schema 暴露和执行分发走统一链路。

### 19.2 新增模型协议

1. 实现 `LlmClient`，把内部 Message/tools 转成目标协议。
2. 把原始流转换成完整的统一 `StreamEvent`，特别处理 tool id、增量 JSON、thinking 和 usage。
3. 在 `ConfigLoader.VALID_PROTOCOLS`、`LlmClient.create`、API key 映射和上下文默认值中接线。
4. 测试配置默认值、请求序列化、流式字段、工具历史回传、错误分类和 usage。

### 19.3 新增 MCP 工具

通常不写 Java 工具类。只需在配置中增加 MCP server，`McpManager` 会发现并包装远程工具，随后复用 ToolRegistry、ToolSearch、权限、Hook 和执行器。

### 19.4 新增运行时模块

当前最大架构注意点是三种入口各自重复装配组件。任何新模块都要明确回答：TUI、Print、Remote 哪些模式支持，权限模式是什么，是否持久化，会不会在退出时释放资源，以及是否需要事件桥接。

## 20. 回答项目问题时的标准口径

### “项目的核心架构是什么？”

回答应围绕两层适配：下层 `LlmClient` 把供应商流统一成 `StreamEvent`；上层 `Agent` 把模型行为统一成 `AgentEvent`，并通过 `ToolRegistry + StreamingExecutor` 完成工具闭环。TUI、Print、Remote 只是不同的 Agent 事件消费者和人机交互适配器。

### “一次 Agent 循环是什么？”

不是简单的 LLM → tool → LLM。完整过程包含指令/通知注入、工具结果预算、自动压缩、deferred schema、流式聚合、usage 锚点、权限和 Hook、并发工具批次、结果写回、错误恢复和取消提交边界。

### “MCP 为什么扩展性好？”

因为 MCP 在本项目里只是远程工具到内部 `Tool` 的适配层。连接、发现和 wrapper 在 `McpManager`；曝光由 `ToolSearch` 控制；执行继续走普通工具链，Agent loop 和 UI 不需要认识某个 MCP server 的协议细节。

### “如何保证安全？”

准确说法是多层缓解，而不是绝对安全：提示词约束 + 工具 category + PermissionChecker 固定优先级 + 用户确认 + Hook + 可选 OS sandbox + 文件状态缓存。Remote 暴露、规则覆盖范围和沙箱平台可用性仍是边界。

### “哪些能力还不算完整？”

优先引用第 18 节。特别不要把存在 `CommandLoader`、`SkillExecutor`、`AgentLoader`、sandbox config 类直接说成这些能力已经在所有入口生效。

## 21. 权威源码索引

| 问题 | 先读这些文件 |
|---|---|
| 程序如何启动 | `TermAgentCli.java` |
| TUI 如何组装全部组件 | `tui/TermAgentModel.java` 的 `initializeProvider` |
| 一次 Agent 迭代 | `agent/Agent.java` 的 `agentLoop` |
| 工具如何调度 | `agent/StreamingExecutor.java` |
| 工具如何注册/延迟曝光 | `tool/ToolRegistry.java`、`tool/impl/ToolSearchTool.java` |
| 消息如何保存 | `conversation/ConversationManager.java`、`Message.java` |
| 模型协议如何选择 | `llm/LlmClient.java` 及四个 client |
| MCP 如何接入 | `mcp/McpManager.java` |
| 权限判断 | `permission/PermissionChecker.java`、`PermissionMode.java` |
| 上下文压缩 | `compact/ContextCompactor.java`、`toolresult/ToolResultBudget.java` |
| 会话恢复 | `session/SessionManager.java` |
| 指令和记忆 | `memory/InstructionLoader.java`、`MemoryManager.java`、`MemoryRecall.java` |
| 子 Agent | `subagent/AgentTool.java`、`SubAgentTaskManager.java`、`ToolFilter.java` |
| 团队 | `teams/TeamManager.java`、`TeamTools.java`、`Coordinator.java` |
| worktree | `worktree/WorktreeManager.java`、`AgentWorktree.java` |
| Print 行为 | `print/PrintMode.java` |
| Remote 协议 | `remote/RemoteServer.java`、`remote/index.html` |
| 配置合并 | `config/ConfigLoader.java`、`AppPaths.java`、各 config bean |

---

本文是项目事实源，不是营销说明，也不是未来设计文档。回答问题时先说明对应运行模式和提交版本，再引用具体类与调用链；对未接线能力、外部系统行为和版本可能变化的模型规格保持明确边界。
