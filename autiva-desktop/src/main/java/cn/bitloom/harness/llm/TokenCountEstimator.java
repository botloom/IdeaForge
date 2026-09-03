package cn.bitloom.harness.llm;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.ModelType;

/**
 * Token 估算器 — 替换 spring-ai 的 JTokkitTokenCountEstimator，直接封装 jtokkit。
 * <p>
 * 默认按 cl100k_base 编码估算；消息级估算覆盖 text、reasoningContent 与工具调用参数。
 */
public final class TokenCountEstimator {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();

    private final Encoding encoding;

    public TokenCountEstimator() {
        this(EncodingType.CL100K_BASE);
    }

    public TokenCountEstimator(EncodingType type) {
        this.encoding = REGISTRY.getEncoding(type);
    }

    public TokenCountEstimator(ModelType modelType) {
        this.encoding = REGISTRY.getEncodingForModel(modelType);
    }

    /** 估算文本 token 数（null 视为 0）。 */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.countTokens(text);
    }

    /** 估算单条消息 token 数（text + reasoning + 工具调用名/参数）。 */
    public int estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        int count = 4; // role 等结构性开销（对齐 spring-ai 的经验值）
        count += estimate(message.getText());
        count += estimate(message.getReasoningContent());
        if (message.getToolCalls() != null) {
            for (ToolCall call : message.getToolCalls()) {
                count += estimate(call.name()) + estimate(call.arguments());
            }
        }
        if (message.getToolResults() != null) {
            for (ToolResult result : message.getToolResults()) {
                count += estimate(result.content());
            }
        }
        return count;
    }

    /** 估算消息列表总 token 数。 */
    public int estimate(Iterable<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage m : messages) {
            total += estimate(m);
        }
        return total;
    }
}
