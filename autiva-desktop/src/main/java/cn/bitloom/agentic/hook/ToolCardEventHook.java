package cn.bitloom.agentic.hook;

import cn.bitloom.agentic.event.EventPublisher;
import cn.bitloom.agentic.event.UICardEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 特殊工具的展示卡片监听 Hook。
 * <p>
 * 拦截 Read/Write/Edit/Command 工具的调用，通过 {@link EventPublisher}（ToolContext 中注入的事件源）
 * 发布 {@link UICardEvent}（{@code TOOL_CARD}）到 agent 事件流：
 * <ul>
 *   <li>{@code beforeToolCall} → {@code CREATED}：工具开始，携带入参明细</li>
 *   <li>{@code afterToolCall} → {@code COMPLETED}/{@code FAILED}：工具结束，仅标记状态（不含结果）</li>
 * </ul>
 * 由 ViewModel 消费这些事件渲染每张工具调用独立的 ToolCallCard。
 * <p>
 * ToolContext 中已由 {@code Agent.runStream} 注入 {@code eventSink} 与 {@code sessionId}。
 * 被权限 Hook 拦截的工具不会走到 {@code afterToolCall}，因此不补发完成事件（卡片保持进行中，可接受）。
 */
@Slf4j
public class ToolCardEventHook implements IAgentHook {

    /** 需要做展示卡片的特殊工具集合。Task 纳入其中以触发 UICardEvent 结束当前流式话语
     * （Task 调用前若有 AI 文本，需在此处闭合该 assistant 冒泡，避免子智能体结束后的
     *  新文本被追加到上方旧冒泡）；Task 自身的 TaskCard 展示不受影响，见 ViewModel 特判。 */
    private static final Set<String> CARDED_TOOLS = Set.of("Read", "Write", "Edit", "Command", "Task");

    /** 每 session 按 FIFO 记录待完成的工具 callId，与 afterToolCall 配对 */
    private final Deque<String> pendingCallIds = new ConcurrentLinkedDeque<>();

    @Override
    public String name() {
        return "ToolCardEventHook";
    }

    @Override
    public int order() {
        return 15; // 位于权限审批 Hook(10) 之后，工具实际执行阶段触发
    }

    @Override
    public ToolCallDecision beforeToolCall(String toolName, String input, ToolContext context) {
        if (!CARDED_TOOLS.contains(toolName)) {
            return ToolCallDecision.proceed(input);
        }
        String sessionId = extractString(context, "sessionId");
        EventPublisher sink = extractEventSink(context);
        if (sink == null || sessionId == null) {
            return ToolCallDecision.proceed(input);
        }
        String callId = UUID.randomUUID().toString();
        pendingCallIds.addLast(callId);
        try {
            sink.publish(UICardEvent.toolCardCreated(sessionId, callId, toolName, input));
        } catch (Exception e) {
            log.warn("[ToolCardEventHook] 发布工具卡片 CREATED 事件失败: tool={}", toolName, e);
        }
        return ToolCallDecision.proceed(input);
    }

    @Override
    public String afterToolCall(String toolName, String result, ToolContext context) {
        if (!CARDED_TOOLS.contains(toolName)) {
            return result;
        }
        String sessionId = extractString(context, "sessionId");
        EventPublisher sink = extractEventSink(context);
        if (sink == null || sessionId == null) {
            return result;
        }
        String callId = pendingCallIds.pollFirst();
        boolean isFailed = result != null
                && result.contains("\"status\":\"error\"");
        try {
            if (isFailed) {
                sink.publish(UICardEvent.toolCardFailed(sessionId, callId, toolName, extractError(result)));
            } else {
                sink.publish(UICardEvent.toolCardCompleted(sessionId, callId, toolName));
            }
        } catch (Exception e) {
            log.warn("[ToolCardEventHook] 发布工具卡片完成事件失败: tool={}", toolName, e);
        }
        return result;
    }

    /** 从工具结果 JSON 提取错误摘要（不含完整 detail）。 */
    private String extractError(String result) {
        if (result == null) {
            return null;
        }
        // 结果可能为 JSON，尝试取 message 字段（ToolResult 结构）
        int idx = result.indexOf("\"message\":");
        if (idx >= 0) {
            int start = result.indexOf('"', idx + "\"message\":".length());
            int end = result.indexOf('"', start + 1);
            if (start >= 0 && end > start) {
                return result.substring(start + 1, end);
            }
        }
        return null;
    }

    private String extractString(ToolContext context, String key) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object value = context.getContext().get(key);
        return value instanceof String s ? s : null;
    }

    private EventPublisher extractEventSink(ToolContext context) {
        if (context == null || context.getContext() == null) {
            return null;
        }
        Object sink = context.getContext().get("eventSink");
        return sink instanceof EventPublisher publisher ? publisher : null;
    }
}
