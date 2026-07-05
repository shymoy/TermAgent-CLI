# 仓库贡献指南

## 项目结构与模块组织

本项目是基于 Java 21 和 Gradle 的应用程序。生产代码位于 `src/main/java/io/github/shymoy/termagent/`，并按职责划分：`agent/` 负责模型与工具的执行循环，`llm/` 存放模型供应商客户端，`tool/` 实现可调用工具，`config/` 负责加载 YAML 配置，`tui/` 和 `remote/` 提供交互界面。运行资源、内置技能和远程页面位于 `src/main/resources/`。测试代码在 `src/test/java/` 中按相同包结构组织。架构图放在 `docs/`；`build/` 是生成目录，不得提交。

## 构建、测试与本地开发

- `./gradlew run`：启动终端交互程序，并连接标准输入。
- `./gradlew test`：运行全部 JUnit 5 测试。
- `./gradlew test --tests io.github.shymoy.termagent.llm.DeepSeekClientTest`：只运行指定测试类。
- `./gradlew build`：完成编译、测试并生成标准构建产物。
- `./gradlew shadowJar`：生成包含全部依赖的 `build/libs/TermAgent-CLI.jar`。
- `java -jar build/libs/TermAgent-CLI.jar`：运行打包后的程序。

统一使用仓库自带的 Gradle Wrapper，不要依赖本机安装的 Gradle 版本。

## 编码风格与命名约定

使用四空格缩进和 UTF-8 编码。类名采用 `PascalCase`，方法和字段采用 `camelCase`，常量采用 `UPPER_SNAKE_CASE`。包名保持在 `io.github.shymoy.termagent` 下，新类应放入职责最匹配的功能包。消息和值对象优先使用不可变 `record`；不同模型供应商的特殊行为优先通过专用子类实现，避免在共享客户端中堆叠条件判断。项目暂未配置自动格式化或静态检查工具，因此修改时应遵循相邻代码风格，并在提交前运行 `git diff --check`。

## 测试规范

测试框架为 JUnit Jupiter 5。测试类使用 `*Test` 命名，测试方法应描述具体行为，例如 `thinkingToolCallKeepsReasoningAndNonNullAssistantContent`。修复缺陷时必须添加回归测试。修改模型供应商适配时，应覆盖配置默认值、请求序列化、流式字段以及工具调用历史回传。提交 Pull Request 前运行完整测试套件。

## 提交与 Pull Request 规范

提交信息采用简洁的 Conventional Commits 风格，例如 `feat: 新增 DeepSeek V4 供应商适配`。类型可使用 `feat`、`fix`、`test`、`docs`、`refactor` 或 `chore`，冒号后写清单一改动。保持每个提交目标明确且可独立审查。Pull Request 应说明用户可见变化、列出验证命令并关联相关 Issue；只有 TUI 或远程页面发生视觉变化时才需要截图。

## 安全与配置

不得提交 API Key 或本地 `.termagent/` 状态。用户配置放在 `~/.termagent/config.yaml`，密钥优先通过 `OPENAI_API_KEY`、`ANTHROPIC_API_KEY` 或 `DEEPSEEK_API_KEY` 等环境变量提供。
