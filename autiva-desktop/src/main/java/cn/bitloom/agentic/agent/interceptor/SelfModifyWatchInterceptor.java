package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.agent.SelfModifiedEvent;
import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.constant.AppConstants;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallDecision;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.util.JsonUtils;
import cn.bitloom.util.AppEvents;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.Set;

/**
 * 自修改监视拦截器（原 SelfModifyWatchHook 的 LoopInterceptor 形态）—
 * Write/Edit 落盘后检测是否修改了"自身的构成文件"，触发热重载。
 * <p>
 * 监视区域（均为外部可写目录，应用启动时从模板复制）：
 * <ul>
 *   <li>skills 目录下的 SKILL.md → 重载技能（SkillManager.loadSkills）</li>
 *   <li>agents 目录下的 agent.md → 重载主智能体定义</li>
 *   <li>subagents 目录下的 agent.md → 重载子智能体定义</li>
 * </ul>
 * 重载后发布 {@link SelfModifiedEvent}，监听方 evict 该 session 的 Agent 缓存，
 * 下一轮消息发送时按新构成重建 Agent。
 * <p>
 * order=13：排在 PermissionInterceptor(10) / FileChangeRecorderInterceptor(12) 之后，
 * 仅对真正执行成功的写操作生效（beforeToolCall 被阻止时 afterToolCall 不会执行）。
 * <p>
 * beforeToolCall / afterToolCall 在 AgentLoop 工具执行阶段成对调用，
 * 用 ThreadLocal 传递本次调用的目标文件路径。
 */
@Slf4j
public class SelfModifyWatchInterceptor implements LoopInterceptor {

    private static final Set<String> MODIFY_TOOLS = Set.of("Write", "Edit");
    private static final ThreadLocal<String> PENDING_PATH = new ThreadLocal<>();

    private final SkillManager skillManager;
    private final AgentDefinitionManager definitionManager;

    public SelfModifyWatchInterceptor(SkillManager skillManager, AgentDefinitionManager definitionManager) {
        this.skillManager = skillManager;
        this.definitionManager = definitionManager;
    }

    @Override
    public String name() {
        return "SelfModifyWatchInterceptor";
    }

    @Override
    public int order() {
        return 13;
    }

    @Override
    public ToolCallDecision beforeToolCall(String toolName, String input, ToolContext context) {
        if (toolName == null || !MODIFY_TOOLS.contains(toolName)) {
            PENDING_PATH.remove();
            return ToolCallDecision.proceed(input);
        }
        String filePath = JsonUtils.extractString(input, "filePath");
        PENDING_PATH.set(filePath);
        return ToolCallDecision.proceed(input);
    }

    @Override
    public String afterToolCall(String toolName, String result, ToolContext context) {
        String filePath = PENDING_PATH.get();
        PENDING_PATH.remove();
        if (filePath == null || filePath.isBlank() || result == null || result.contains("\"ERROR\"")) {
            return result;
        }
        try {
            handleSelfModify(filePath, extractString(context, "sessionId"));
        } catch (Exception e) {
            // 热重载失败不阻断工具结果，仅失去当轮生效能力
            log.warn("[SelfModifyWatch] 热重载失败，继续返回工具结果: file={}", filePath, e);
        }
        return result;
    }

    private void handleSelfModify(String filePath, String sessionId) {
        Path path = Path.of(filePath).toAbsolutePath().normalize();
        String fileName = path.getFileName() != null ? path.getFileName().toString() : "";

        if ("SKILL.md".equals(fileName) && startsWithDir(path, AppConstants.Base.SKILLS_DIR)) {
            skillManager.loadSkills();
            publish(sessionId, "技能已重载: " + filePath);
            return;
        }
        if ("agent.md".equals(fileName)) {
            if (startsWithDir(path, AppConstants.Base.AGENTS_DIR)) {
                String agentId = path.getParent().getFileName().toString();
                if (definitionManager.reloadDefinition(agentId)) {
                    publish(sessionId, "主智能体定义已重载: " + agentId);
                }
                return;
            }
            if (startsWithDir(path, AppConstants.Base.SUBAGENTS_DIR)) {
                String subagentName = path.getParent().getFileName().toString();
                if (definitionManager.reloadDefinition(subagentName)) {
                    publish(sessionId, "子智能体定义已重载: " + subagentName);
                }
            }
        }
    }

    private void publish(String sessionId, String detail) {
        log.info("[SelfModifyWatch] {} (sessionId={})", detail, sessionId);
        if (sessionId != null) {
            AppEvents.publish(new SelfModifiedEvent(sessionId, detail));
        }
    }

    private boolean startsWithDir(Path path, Path dir) {
        return path.startsWith(dir.toAbsolutePath().normalize());
    }

    private String extractString(ToolContext context, String key) {
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        return value instanceof String s ? s : null;
    }
}
