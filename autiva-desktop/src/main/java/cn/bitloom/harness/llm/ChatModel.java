package cn.bitloom.harness.llm;

import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 模型适配器 seam — 对标 dsh llm/llm 的 ChatModel 接口。
 * <p>
 * 只有一个流式方法；阻塞调用由调用方 block 或由 AgentLoop 消费。
 * 适配器实现负责：HTTP 传输、SSE 解析、工具调用增量拼装、finishReason 归一。
 *
 * @param messages 本轮完整消息（含 system）
 * @param tools    可用工具规格（空列表表示无工具）
 * @param options  调用选项
 */
public interface ChatModel {

    Flux<ChatChunk> stream(List<ChatMessage> messages, List<ToolSpec> tools, ChatOptions options);

    /** 适配器显示名（日志/自省用）。 */
    default String modelName() {
        return getClass().getSimpleName();
    }
}
