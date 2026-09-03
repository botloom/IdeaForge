package cn.bitloom.harness.tool;

import cn.bitloom.harness.llm.ToolSpec;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具执行管线 — 替换 AutivaToolCallingManager（脱离 spring-ai ToolCallingManager 接口）。
 * <p>
 * 职责：
 * <ul>
 *   <li>工具注册表（名称 → ToolCallback），产出 {@link ToolSpec} 列表供模型调用</li>
 *   <li>执行单个 {@link cn.bitloom.harness.llm.ToolCall}：幻觉工具名友好纠错 +
 *       异常兜底转错误 ToolResult，而非裸抛</li>
 * </ul>
 * <p>
 * 工具调用拦截（审批/预算/卡片事件等）不在此层：由 AgentLoop 经 LoopInterceptor 的
 * beforeToolCall/afterToolCall 接缝处理。
 */
@Slf4j
public class ToolExecutor {

    private final Map<String, ToolCallback> tools = new LinkedHashMap<>();

    public ToolExecutor(List<ToolCallback> callbacks) {
        for (ToolCallback callback : callbacks) {
            String name = callback.definition().name();
            if (tools.containsKey(name)) {
                throw new IllegalStateException("工具重名: " + name);
            }
            tools.put(name, callback);
        }
    }

    /** 已注册工具回调列表（保持注册顺序）。 */
    public List<ToolCallback> callbacks() {
        return List.copyOf(tools.values());
    }

    /** 已注册工具名集合。 */
    public Set<String> toolNames() {
        return Set.copyOf(tools.keySet());
    }

    /** 按名查找（未命中返回 null）。 */
    public ToolCallback find(String toolName) {
        return toolName == null ? null : tools.get(toolName);
    }

    /** 产出提供给模型的工具规格列表。 */
    public List<ToolSpec> toToolSpecs() {
        return tools.values().stream()
                .map(tc -> {
                    ToolDefinition d = tc.definition();
                    return ToolSpec.of(d.name(), d.description(), d.inputSchema());
                })
                .toList();
    }

    /**
     * 执行单个工具调用。
     * <p>
     * 找不到工具 → 友好错误 ToolResult（含可用工具列表），让 LLM 有机会自我纠正；
     * 执行异常 → 兜底错误 ToolResult，不裸抛。
     */
    public cn.bitloom.harness.llm.ToolResult execute(cn.bitloom.harness.llm.ToolCall call,
                                                     ToolContext context) {
        String toolName = call.name();
        String input = call.arguments() == null || call.arguments().isBlank()
                ? "{}" : call.arguments();

        ToolCallback callback = find(toolName);
        if (callback == null) {
            log.warn("[ToolCall] 工具不存在: {} - 可用工具: {}", toolName, tools.keySet());
            return new cn.bitloom.harness.llm.ToolResult(
                    call.id(), toolName, ToolResult.toolNotFound(toolName, tools.keySet()).toJson());
        }

        String result;
        try {
            result = callback.call(input, context != null ? context : ToolContext.empty());
        } catch (IllegalStateException ex) {
            // 可能是 JSON 参数解析失败，也可能是工具内部抛出的 IllegalStateException（如 shell 启动失败）
            log.error("[ToolCall] 工具 {} 抛出 IllegalStateException: {}", toolName, ex.getMessage(), ex);
            result = ToolResult.error("工具执行异常: " + ex.getMessage(),
                    Map.of("raw_input", input)).toJson();
        } catch (Exception ex) {
            log.error("[ToolCall] 工具 {} 抛出未预期异常 {}: {}", toolName,
                    ex.getClass().getName(), ex.getMessage(), ex);
            result = ToolResult.error("工具执行未预期异常: "
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(),
                    Map.of("raw_input", input)).toJson();
        }
        return new cn.bitloom.harness.llm.ToolResult(call.id(), toolName, result);
    }
}
