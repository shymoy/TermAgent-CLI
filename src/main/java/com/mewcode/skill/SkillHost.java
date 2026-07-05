

package com.mewcode.skill;

import com.mewcode.tool.ToolRegistry;

import java.util.function.Predicate;

/**

 * 代理切片指出执行者需要驱动内联模式技能。

 * 声明为接口，因此技能包不会导入代理

 * 包（避免循环依赖）。

 */
public interface SkillHost {

    void activateSkill(String name, String body);

    void setToolFilter(Predicate<String> filter);

    ToolRegistry toolRegistry();

    /**

     * 记录该技能运行的情况，因此其SOP本体可以在运行后重新附着

     * 第 2 层压缩会擦除转录本。主机默认无操作

     * 不跟踪恢复状态。

     */
    default void recordSkillInvocation(String name, String body) {}
}

