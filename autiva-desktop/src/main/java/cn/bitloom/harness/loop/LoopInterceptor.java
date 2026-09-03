package cn.bitloom.harness.loop;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.tool.ToolCallDecision;
import cn.bitloom.harness.tool.ToolContext;

/**
 * 循环拦截器 — 统一接缝，合并原 IAgentHook 与 Spring AI Advisor 两套机制。
 * <p>
 * 由 {@link AgentLoop} 按固定时序调用：
 * <ol>
 *   <li>{@link #beforeRound}：每个用户消息触发一次（工具循环开始前）</li>
 *   <li>{@link #beforeModelCall}：每次模型调用前（工具循环中可能多次），可改写请求</li>
 *   <li>{@link #afterModelCall}：每次模型流结束后（携带聚合完成的完整 assistant 消息）</li>
 *   <li>{@link #beforeToolCall} / {@link #afterToolCall}：每个工具调用前后</li>
 *   <li>{@link #filterStreamChunk}：每个流式文本增量下发前</li>
 *   <li>{@link #afterRound}：每个用户消息触发一次（最终响应生成后）</li>
 * </ol>
 */
public interface LoopInterceptor {

    /** 拦截器名称，默认取类名。 */
    default String name() {
        return this.getClass().getSimpleName();
    }

    /** 执行顺序，数值越小越先执行。 */
    default int order() {
        return 0;
    }

    /** 模型调用前拦截，可修改请求（返回 null 表示保持不变）。 */
    default ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
        return request;
    }

    /**
     * 模型调用后回调（流已完整聚合）。
     *
     * @param request          本次调用使用的请求（经 beforeModelCall 改写后）
     * @param assistantMessage 聚合完成的完整 assistant 消息（含 toolCalls / finishReason）
     */
    default void afterModelCall(ChatRequest request, ChatMessage assistantMessage, LoopContext ctx) {
    }

    /**
     * 工具调用前拦截，可修改输入或阻止调用。
     *
     * @param toolName 工具名称
     * @param input    原始输入参数（JSON 字符串）
     * @param context  工具上下文（可获取 sessionId / projectPath / eventSink 等）
     * @return 工具调用决策：proceed(input) 继续（可带修改后的 input），block(reason) 阻止
     */
    default ToolCallDecision beforeToolCall(String toolName, String input, ToolContext context) {
        return ToolCallDecision.proceed(input);
    }

    /**
     * 工具调用后回调，可修改结果。
     *
     * @param toolName 工具名称
     * @param result   原始结果（JSON 字符串，通常是 ToolResult.toJson()）
     * @param context  工具上下文
     * @return 修改后的结果（返回 null 表示不修改，保持原 result）
     */
    default String afterToolCall(String toolName, String result, ToolContext context) {
        return result;
    }

    /**
     * 流式响应文本过滤：每个文本增量下发前调用。
     * 跨 chunk 的滑动窗口过滤由实现方自行维护。
     *
     * @param text 当前增量的文本内容（可能为 null）
     * @return 过滤后的文本（返回 null 表示保持原文不变）
     */
    default String filterStreamChunk(String text) {
        return null;
    }

    /** 每轮对话开始前调用（每个用户消息只触发一次，先于全部 beforeModelCall）。 */
    default void beforeRound(LoopContext ctx) {
    }

    /** 每轮对话结束后调用（每个用户消息只触发一次，最终响应生成后）。 */
    default void afterRound(LoopContext ctx) {
    }
}
