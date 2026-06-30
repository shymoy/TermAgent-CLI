// 来源：公众号@小林coding
// 后端八股网站：xiaolincoding.com
// Agent网站：xiaolinnote.com
// 简历模版：jianli.xiaolinnote.com


package com.mewcode.permission;

import com.mewcode.tool.ToolCategory;

/**
 * Agent 的权限运行模式。
 *
 * <p>权限模式根据工具类别提供兜底决策。它只在 {@link PermissionChecker} 前面的
 * 特殊规则、安全检查、路径检查和 YAML 规则都未命中时生效，因此 {@link #BYPASS}
 * 也不代表能够绕过所有硬拒绝规则。</p>
 */
public enum PermissionMode {

    /** 默认模式：读取自动允许，写入和命令执行需要用户确认。 */
    DEFAULT,

    /** 接受编辑模式：读取和文件写入自动允许，命令执行仍需用户确认。 */
    ACCEPT_EDITS,

    /**
     * 计划模式：常规分类决策与默认模式相同；规划工具和计划文件的特例由
     * {@link PermissionChecker} 在进入本方法前处理。
     */
    PLAN,

    /** 绕过常规分类询问并自动允许，但仍受更高优先级的硬拒绝规则约束。 */
    BYPASS;

    /**
     * 根据当前权限模式和工具类别生成兜底决策。
     *
     * @param category 工具类别：读取、写入或命令执行
     * @return 系统对该类工具的允许、拒绝或询问决策
     */
    public Decision decide(ToolCategory category) {
        return switch (this) {
            case DEFAULT -> switch (category) {
                case READ -> Decision.ALLOW;
                case WRITE, COMMAND -> Decision.ASK;
            };
            case ACCEPT_EDITS -> switch (category) {
                case READ, WRITE -> Decision.ALLOW;
                case COMMAND -> Decision.ASK;
            };
            case PLAN -> DEFAULT.decide(category);
            case BYPASS -> Decision.ALLOW;
        };
    }

    /**
     * 权限系统的内部决策，不等同于用户在确认框中的响应。
     */
    public enum Decision {
        /** 直接执行工具。 */
        ALLOW,
        /** 拒绝工具调用，并将拒绝原因作为工具错误返回给模型。 */
        DENY,
        /** 暂停当前工具调用，请求用户确认。 */
        ASK
    }
}
