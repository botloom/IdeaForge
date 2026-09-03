package cn.bitloom.harness.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import cn.bitloom.harness.llm.json.ChatMessageDeserializer;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一消息词汇表 — 对标 dsh llm/llm 的 message vocabulary，替换 spring-ai 的
 * SystemMessage/UserMessage/AssistantMessage/ToolResponseMessage 四件套。
 * <p>
 * 一条消息按角色取用不同字段的子集：
 * <ul>
 *   <li>SYSTEM/USER：仅 {@link #text}</li>
 *   <li>ASSISTANT：{@link #text} + {@link #reasoningContent} + {@link #toolCalls} + metadata(finishReason)</li>
 *   <li>TOOL：{@link #toolResults}</li>
 * </ul>
 * <p>
 * 可变 POJO：流式聚合与拦截器改写场景需要原地构建/复制；Jackson 按角色往返序列化。
 */
@Getter
@NoArgsConstructor
@JsonDeserialize(using = ChatMessageDeserializer.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChatMessage {

    private Role role;
    private String text;
    /** 思考内容（reasoning_content，思考型模型专用；仅 ASSISTANT） */
    private String reasoningContent;
    /** 模型发起的工具调用（仅 ASSISTANT） */
    private List<ToolCall> toolCalls;
    /** 工具执行结果（仅 TOOL） */
    private List<ToolResult> toolResults;
    /** 附加元数据（finishReason / 合成标记等；仅 ASSISTANT 使用） */
    private Map<String, Object> metadata;

    public static ChatMessage system(String text) {
        ChatMessage m = new ChatMessage();
        m.role = Role.SYSTEM;
        m.text = text;
        return m;
    }

    public static ChatMessage user(String text) {
        ChatMessage m = new ChatMessage();
        m.role = Role.USER;
        m.text = text;
        return m;
    }

    public static ChatMessage assistant(String text) {
        ChatMessage m = new ChatMessage();
        m.role = Role.ASSISTANT;
        m.text = text;
        return m;
    }

    public static ChatMessage assistant(String text, String reasoningContent,
                                        List<ToolCall> toolCalls, String finishReason) {
        ChatMessage m = new ChatMessage();
        m.role = Role.ASSISTANT;
        m.text = text;
        m.reasoningContent = reasoningContent;
        m.toolCalls = toolCalls;
        if (finishReason != null) {
            m.metadata = new HashMap<>();
            m.metadata.put("finishReason", finishReason);
        }
        return m;
    }

    public static ChatMessage toolResults(List<ToolResult> results) {
        ChatMessage m = new ChatMessage();
        m.role = Role.TOOL;
        m.toolResults = results;
        return m;
    }

    /** 设置 finishReason（写入 metadata；流式聚合用）。 */
    public void setFinishReason(String finishReason) {
        if (finishReason == null) {
            return;
        }
        if (metadata == null) {
            metadata = new HashMap<>();
        }
        metadata.put("finishReason", finishReason);
    }

    /** 便捷访问：metadata 中的 finishReason。 */
    public String finishReason() {
        Object v = metadata != null ? metadata.get("finishReason") : null;
        return v != null ? v.toString() : null;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    /** 添加一个工具调用（流式聚合用）。 */
    public void addToolCall(ToolCall call) {
        if (toolCalls == null) {
            toolCalls = new ArrayList<>();
        }
        toolCalls.add(call);
    }

    /** 追加文本增量（流式聚合用）。 */
    public void appendText(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        this.text = this.text == null ? delta : this.text + delta;
    }

    /** 追加思考增量（流式聚合用）。 */
    public void appendReasoning(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        this.reasoningContent = this.reasoningContent == null ? delta : this.reasoningContent + delta;
    }

    /** 深拷贝（拦截器改写请求时不污染原消息）。 */
    public ChatMessage copy() {
        ChatMessage m = new ChatMessage();
        m.role = role;
        m.text = text;
        m.reasoningContent = reasoningContent;
        m.toolCalls = toolCalls != null ? new ArrayList<>(toolCalls) : null;
        m.toolResults = toolResults != null ? new ArrayList<>(toolResults) : null;
        m.metadata = metadata != null ? new HashMap<>(metadata) : null;
        return m;
    }

    /** 反序列化 setter 语义（Jackson 经反序列化器调用）。 */
    public void set(Role role, String text, String reasoningContent, List<ToolCall> toolCalls,
             List<ToolResult> toolResults, Map<String, Object> metadata) {
        this.role = role;
        this.text = text;
        this.reasoningContent = reasoningContent;
        this.toolCalls = toolCalls;
        this.toolResults = toolResults;
        this.metadata = metadata;
    }
}
