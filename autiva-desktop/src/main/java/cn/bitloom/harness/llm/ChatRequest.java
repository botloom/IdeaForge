package cn.bitloom.harness.llm;

import java.util.List;

/**
 * 模型调用请求 — 消息 + 工具规格 + 选项。
 * <p>
 * AgentLoop 与 LoopInterceptor 围绕此对象组装/改写请求。
 *
 * @param messages 完整消息列表（含 system 与历史）
 * @param tools    可用工具规格（空列表表示无工具）
 * @param options  调用选项
 */
public record ChatRequest(List<ChatMessage> messages, List<ToolSpec> tools, ChatOptions options) {

    public ChatRequest {
        messages = messages != null ? List.copyOf(messages) : List.of();
        tools = tools != null ? List.copyOf(tools) : List.of();
    }

    public static ChatRequest of(List<ChatMessage> messages, List<ToolSpec> tools, ChatOptions options) {
        return new ChatRequest(messages, tools, options);
    }

    /** 返回替换了消息列表的新请求（拦截器改写用）。 */
    public ChatRequest withMessages(List<ChatMessage> newMessages) {
        return new ChatRequest(newMessages, tools, options);
    }

    /** 返回替换了工具列表的新请求。 */
    public ChatRequest withTools(List<ToolSpec> newTools) {
        return new ChatRequest(messages, newTools, options);
    }

    /** 返回替换了选项的新请求。 */
    public ChatRequest withOptions(ChatOptions newOptions) {
        return new ChatRequest(messages, tools, newOptions);
    }
}
