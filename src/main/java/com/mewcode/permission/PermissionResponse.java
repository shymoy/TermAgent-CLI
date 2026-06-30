
package com.mewcode.permission;

/**
 * 用户对权限确认请求的响应。
 *
 * <p>只有权限检查结果为 {@link PermissionMode.Decision#ASK} 时，上层执行器才会
 * 请求用户作出该选择。它描述的是用户决定，而不是权限检查器的内部决策。</p>
 */
public enum PermissionResponse {
    /** 仅允许执行当前这一次工具调用。 */
    ALLOW,

    /**
     * 允许当前调用，并在本次会话中记住相同“工具名 + 核心参数”的操作。
     * 该授权默认只保存在内存中，不会自动写入 permissions.local.yaml。
     */
    ALLOW_ALWAYS,

    /** 拒绝当前工具调用；拒绝结果会返回给模型，Agent 循环可以继续调整方案。 */
    DENY
}
