
package com.mewcode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件“先读后改”状态缓存。
 *
 * <p>ReadFile 成功后记录文件内容和修改时间；EditFile 或 WriteFile 修改已有文件前，
 * 通过该缓存确认模型已经读过文件，并且文件在读取后没有被外部程序再次修改。</p>
 *
 * <p>缓存使用绝对路径作为键，并采用并发容器，以支持多个只读工具并行更新状态。</p>
 */
public class FileStateCache {

    /** 模型最近一次读取或成功写入时看到的文件内容及修改时间。 */
    public record FileState(String content, long mtimeMs) {}

    private final ConcurrentHashMap<String, FileState> cache = new ConcurrentHashMap<>();

    /** ReadFile 读取成功后调用，建立后续修改所需的文件状态基线。 */
    public void record(String absPath, String content, long mtimeMs) {
        cache.put(absPath, new FileState(content, mtimeMs));
    }

    /** EditFile 或 WriteFile 修改成功后调用，用新内容和磁盘修改时间刷新基线。 */
    public void update(String absPath, String newContent) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            // 文件已经写入成功时，即使读取修改时间失败，也保留一份可继续使用的近似基线。
            mtime = System.currentTimeMillis();
        }
        cache.put(absPath, new FileState(newContent, mtime));
    }

    /** 获取已记录的文件状态；文件从未读取或写入时返回 null。 */
    public FileState get(String absPath) {
        return cache.get(absPath);
    }

    /**
     * 修改文件前验证缓存状态。
     *
     * @return 验证通过时返回 null；需要阻止修改时返回可直接交给模型的错误信息
     */
    public String validate(String absPath) {
        FileState state = cache.get(absPath);
        if (state == null) {
            // 没有读取基线时拒绝修改，避免模型覆盖自己尚未看过的已有内容。
            return "Error: file has not been read yet. Read it first before editing.";
        }
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            // 文件可能已被删除，交给具体写入工具后续的存在性检查处理。
            return null;
        }
        if (currentMtime > state.mtimeMs()) {
            // 文件在模型读取后被外部修改，要求重新读取以避免覆盖他人的更新。
            return "Error: file has been modified since last read. Read it again before editing.";
        }
        return null;
    }
}
