package cn.bitloom.harness.tool;

/**
 * 工具回调 — 对标 spring-ai 的 ToolCallback / dsh core/tools 的 Tool。
 * <p>
 * {@link #call} 接收原始入参 JSON，返回文本结果（通常是 {@link ToolResult} 的 JSON 序列化）。
 * 实现可选择抛出异常：由 {@link ToolExecutor} 统一捕获并转为错误 ToolResult。
 */
public interface ToolCallback {

    /** 工具定义（名称/描述/schema）。 */
    ToolDefinition definition();

    /**
     * 执行工具。
     *
     * @param inputJson LLM 给出的入参 JSON（可能为空串，视作 "{}"）
     * @param context   执行上下文（sessionId / projectPath / eventSink 等）
     * @return 文本结果（LLM 消费）
     */
    String call(String inputJson, ToolContext context) throws Exception;
}
