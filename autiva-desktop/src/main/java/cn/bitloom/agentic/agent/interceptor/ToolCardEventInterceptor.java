package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.agentic.event.EventPublisher;
import cn.bitloom.agentic.event.UICardEvent;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallDecision;
import cn.bitloom.harness.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.Deque;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 工具调用展示卡片监听拦截器（原 ToolCardEventHook 的 LoopInterceptor 形态）。
 * <p>
 * 对所有工具调用，通过 {@link EventPublisher}（ToolContext 中注入的事件源）
 * 发布 {@link UICardEvent}（{@code TOOL_CARD}）到 agent 事件流：
 * <ul>
 *   <li>{@code beforeToolCall} → {@code CREATED}：工具开始，携带入参明细</li>
 *   <li>{@code afterToolCall} → {@code COMPLETED}/{@code FAILED}：工具结束，仅标记状态（不含结果）</li>
 * </ul>
 * ViewModel 消费 CREATED 事件分隔当前思考段（finishStreamingText），
 * 并按工具名决定是否为 Read/Write/Edit/Command 渲染 ToolCallCard。
 * <p>
 * ToolContext 中已由 AgentLoop 经 LoopContext 注入 {@code eventSink} 与 {@code sessionId}。
 * 被权限拦截器阻止的工具不会走到 {@code afterToolCall}，因此不补发完成事件
 * （卡片保持进行中，可接受）。
 */
@Slf4j
public class ToolCardEventInterceptor implements LoopInterceptor {

    /** 每 session 按 FIFO 记录待完成的工具 callId，与 afterToolCall 配对 */
    private final Deque<String> pendingCallIds = new ConcurrentLinkedDeque<>();

    @Override
    public String name() {
        return "ToolCardEventInterceptor";
    }

    @Override
    public int order() {
        return 15; // 位于权限审批拦截器(10) 之后，工具实际执行阶段触发
    }

    @Override
    public ToolCallDecision beforeToolCall(String toolName, String input, ToolContext context) {
        // 所有工具都发布 CREATED：ViewModel 借此分隔当前思考段（finishStreamingText），
        // 避免“思考→工具→思考”中下一段思考覆盖上一段；是否展示工具组卡由 ViewModel 按 toolName 决定
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
            log.warn("[ToolCardEventInterceptor] 发布工具卡片 CREATED 事件失败: tool={}", toolName, e);
        }
        return ToolCallDecision.proceed(input);
    }

    @Override
    public String afterToolCall(String toolName, String result, ToolContext context) {
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
            log.warn("[ToolCardEventInterceptor] 发布工具卡片完成事件失败: tool={}", toolName, e);
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
        if (context == null) {
            return null;
        }
        Object value = context.get(key);
        return value instanceof String s ? s : null;
    }

    private EventPublisher extractEventSink(ToolContext context) {
        if (context == null) {
            return null;
        }
        Object sink = context.get("eventSink");
        return sink instanceof EventPublisher publisher ? publisher : null;
    }
}
