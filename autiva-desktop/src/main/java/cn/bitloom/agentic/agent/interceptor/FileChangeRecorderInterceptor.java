package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.agentic.snapshot.TurnSnapshotStore;
import cn.bitloom.constant.AppConstants;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallDecision;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 文件变更快照拦截器（原 FileChangeRecorderHook 的 LoopInterceptor 形态）—
 * 在 Write/Edit 真正执行前把原文件内容按轮落盘，为「撤回按钮」提供数据源。
 * <p>
 * order=12：排在 PermissionInterceptor(10) 之后，被审批拦截的调用不会走到这里，
 * 快照只记录真正执行的修改。
 * <p>
 * 快照布局（会话目录下）：
 * <pre>
 * turns/{turnId}/turn.json          // {"userMessageEventId": ...} 关联撤回定位（首次快照时写入）
 * turns/{turnId}/snapshot-*.json    // {"path","ref","existed"}：existed=false 表示 AI 新建的文件
 * turns/{turnId}/{ref}.bin          // 原文件内容副本（内容寻址）
 * </pre>
 * 同轮同文件只保留最早一份快照（即该轮开始前的状态）；撤回时按其恢复。
 * Command/Process 等命令类工具造成的文件修改无法精确快照，不在撤回范围内。
 */
@Slf4j
public class FileChangeRecorderInterceptor implements LoopInterceptor {

    private static final Set<String> SNAPSHOT_TOOLS = Set.of("Write", "Edit");

    @Override
    public String name() {
        return "FileChangeRecorderInterceptor";
    }

    @Override
    public int order() {
        return 12;
    }

    @Override
    public ToolCallDecision beforeToolCall(String toolName, String input, ToolContext context) {
        if (toolName == null || !SNAPSHOT_TOOLS.contains(toolName)) {
            return ToolCallDecision.proceed(input);
        }
        String turnId = extractString(context, "turnId");
        String sessionId = extractString(context, "sessionId");
        if (turnId == null || sessionId == null) {
            // 无轮次上下文（如部分自动续轮）：不快照，仅失去该轮撤回能力
            return ToolCallDecision.proceed(input);
        }
        String filePath = JsonUtils.extractString(input, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolCallDecision.proceed(input);
        }

        try {
            Path turnDir = AppConstants.Session.turnsDir(sessionId).resolve(turnId);
            Files.createDirectories(turnDir);
            writeTurnMetaIfAbsent(turnDir, extractString(context, "userMessageEventId"));

            if (hasSnapshotFor(turnDir, filePath)) {
                // 同轮已快照过该文件：保留最早一份（轮开始前状态）
                return ToolCallDecision.proceed(input);
            }

            Path path = Path.of(filePath);
            boolean existed = Files.exists(path) && !Files.isDirectory(path);
            String ref = existed ? TurnSnapshotStore.put(turnDir, Files.readAllBytes(path)) : null;

            ObjectNode snapshot = JsonUtils.createObject();
            snapshot.put("path", filePath);
            snapshot.put("ref", ref);
            snapshot.put("existed", existed);
            String name = "snapshot-" + UUID.randomUUID().toString().substring(0, 8) + ".json";
            Files.writeString(turnDir.resolve(name), JsonUtils.toJson(snapshot));
            log.debug("[FileChangeRecorder] 快照: turn={}, file={}, existed={}", turnId, filePath, existed);
        } catch (Exception e) {
            // 快照失败不阻断任务执行，仅该文件失去撤回能力
            log.warn("[FileChangeRecorder] 快照失败，继续执行: tool={}, file={}", toolName, filePath, e);
        }
        return ToolCallDecision.proceed(input);
    }

    /**
     * 首次快照时写入轮次元数据，把 turnId 关联到触发本轮的用户消息事件 ID（撤回定位键）。
     */
    private void writeTurnMetaIfAbsent(Path turnDir, String userMessageEventId) throws Exception {
        Path meta = turnDir.resolve("turn.json");
        if (Files.exists(meta)) {
            return;
        }
        ObjectNode node = JsonUtils.createObject();
        node.put("userMessageEventId", userMessageEventId);
        Files.writeString(meta, JsonUtils.toJson(node));
    }

    /** 同轮是否已快照过该文件（读取已有 snapshot-*.json 比对 path） */
    private boolean hasSnapshotFor(Path turnDir, String filePath) throws Exception {
        try (Stream<Path> files = Files.list(turnDir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("snapshot-") && n.endsWith(".json");
                    })
                    .map(p -> JsonUtils.extractString(readQuietly(p), "path"))
                    .anyMatch(filePath::equals);
        }
    }

    private String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "";
        }
    }

    private String extractString(ToolContext context, String key) {
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        return value instanceof String s ? s : null;
    }
}
