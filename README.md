# TermAgent-CLI

[TermAgent-CLI](https://github.com/shymoy/TermAgent-CLI) 是一个基于 Java 21 的终端 AI 编程助手，
提供多模型接入、工具调用、会话恢复、上下文压缩、技能、MCP、子 Agent、团队协作、
Git worktree 隔离以及远程 Web 界面。

## 构建与运行

```bash
./gradlew test
./gradlew run
./gradlew shadowJar
java -jar build/libs/termagent-cli.jar
```

常用参数：

- `-p "prompt"`：非交互执行一次请求。
- `--output-format=stream-json`：以 JSON 事件流输出。
- `--remote[=addr]`：启动 HTTP 与 WebSocket 服务。
- `--version`：显示版本。
- `--help`：显示参数。

## 配置

默认按以下优先级合并配置：

1. `~/.termagent/config.yaml`
2. `<项目>/.termagent/config.yaml`
3. `<项目>/.termagent/config.local.yaml`

也可以通过 `TERMAGENT_CONFIG` 指定配置文件。API Key 建议使用
`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`DEEPSEEK_API_KEY` 等环境变量提供。

## 旧版数据兼容

新数据统一写入 `.termagent`。读取配置、权限、指令、技能、命令、Agent 和会话时，
如果新路径缺失，会兼容旧版 `.mewcode` 数据。继续写入旧会话或权限文件前，
程序会按文件迁入新目录，不会整体复制大型 worktree。

旧 `MEWCODE_CONFIG`、`MEWCODE_*` Hook 变量以及 `MEWCODE.md` 指令文件仍可使用，
但新项目应采用 `TERMAGENT_CONFIG`、`TERMAGENT_*` 和 `TERMAGENT.md`。

## 开发

生产代码位于 `src/main/java/io/github/shymoy/termagent/`，测试使用 JUnit Jupiter 5。
提交前请运行：

```bash
./gradlew test
git diff --check
```
