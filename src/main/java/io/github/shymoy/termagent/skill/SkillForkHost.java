

package io.github.shymoy.termagent.skill;

import io.github.shymoy.termagent.conversation.Message;

import java.util.List;

/**

 * 扩展了 {@link SkillHost} 运行独立子代理的能力。

 * 由TUI层实现（拥有LLM客户端+代理

 * 构造函数）并传递到 {@link SkillExecutor#executeFork} 中。

 */
public interface SkillForkHost extends SkillHost {

    String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model);

    List<Message> snapshotParentMessages();
}

