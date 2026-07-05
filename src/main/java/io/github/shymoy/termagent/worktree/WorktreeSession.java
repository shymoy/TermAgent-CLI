

package io.github.shymoy.termagent.worktree;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**

 * 跟踪活动工作树会话的状态。

 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorktreeSession(
        @JsonProperty("original_cwd") String originalCwd,
        @JsonProperty("worktree_path") String worktreePath,
        @JsonProperty("worktree_name") String worktreeName,
        @JsonProperty("worktree_branch") String worktreeBranch,
        @JsonProperty("original_branch") String originalBranch,
        @JsonProperty("original_head_commit") String originalHeadCommit,
        @JsonProperty("session_id") String sessionId,
        @JsonProperty("creation_duration_ms") long creationDurationMs
) {}

