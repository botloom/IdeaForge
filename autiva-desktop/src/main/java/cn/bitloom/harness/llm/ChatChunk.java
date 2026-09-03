package cn.bitloom.harness.llm;

import java.util.List;

/**
 * 流式响应分片。
 * <p>
 * 文本/思考以增量（delta）形式逐片下发；工具调用在终帧一次性给出
 * （客户端内部完成增量拼装，消费方无需处理分片拼接）。终帧判定：
 * {@link #finishReason} 非 null。
 *
 * @param deltaText        文本增量（可能为 null）
 * @param deltaReasoning   思考增量（可能为 null）
 * @param toolCalls        完整工具调用列表（仅终帧且 finishReason=tool_calls 时非 null）
 * @param finishReason     结束原因（stop / tool_calls / length 等；非终帧为 null）
 * @param usage            token 用量（终帧可能携带）
 */
public record ChatChunk(String deltaText, String deltaReasoning, List<ToolCall> toolCalls,
                        String finishReason, Usage usage) {

    public static ChatChunk text(String delta) {
        return new ChatChunk(delta, null, null, null, null);
    }

    public static ChatChunk reasoning(String delta) {
        return new ChatChunk(null, delta, null, null, null);
    }

    public static ChatChunk terminal(String finishReason, List<ToolCall> toolCalls, Usage usage) {
        return new ChatChunk(null, null, toolCalls, finishReason, usage);
    }

    public boolean isTerminal() {
        return finishReason != null;
    }
}
