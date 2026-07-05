
package com.mewcode.toolresult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 以 JSONL 格式持久化 {@link ContentReplacementRecord}。
 * 每个会话使用 {@code <sessionDir>/replacement_records.jsonl} 文件，
 * 采用只追加写入，每行一条记录。文件不存在时返回空列表，
 * 使会话恢复和冷启动可共用同一条代码路径。
 */
public final class ReplacementRecordsIO {

    public static final String RECORDS_FILENAME = "replacement_records.jsonl";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReplacementRecordsIO() {}

    public static void append(Path sessionDir, List<ContentReplacementRecord> records) throws IOException {
        if (records.isEmpty()) return;
        Files.createDirectories(sessionDir);
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        try (BufferedWriter w = Files.newBufferedWriter(
                file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND
        )) {
            for (ContentReplacementRecord r : records) {
                String kind = r.kind() == null || r.kind().isEmpty()
                        ? ContentReplacementRecord.KIND_TOOL_RESULT
                        : r.kind();
                ContentReplacementRecord normalized = new ContentReplacementRecord(
                        kind, r.toolUseId(), r.replacement());
                try {
                    w.write(MAPPER.writeValueAsString(normalized));
                } catch (JsonProcessingException e) {
                    throw new IOException(e);
                }
                w.write('\n');
            }
        }
    }

    public static List<ContentReplacementRecord> load(Path sessionDir) throws IOException {
        Path file = sessionDir.resolve(RECORDS_FILENAME);
        if (!Files.exists(file)) return Collections.emptyList();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<ContentReplacementRecord> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line.isEmpty()) continue;
            out.add(MAPPER.readValue(line, ContentReplacementRecord.class));
        }
        return out;
    }
}
