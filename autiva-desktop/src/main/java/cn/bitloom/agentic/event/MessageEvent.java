package cn.bitloom.agentic.event;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.llm.ToolCall;
import cn.bitloom.harness.llm.ToolResult;
import cn.bitloom.harness.llm.json.ChatMessageDeserializer;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@SuperBuilder
@Jacksonized
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public final class MessageEvent extends AbstractEvent {

    public static final String METADATA_SYNTHETIC = "synthetic";

    @Builder.Default
    private EventTypeEnum eventType = EventTypeEnum.MESSAGE;

    @Builder.Default
    private String id = UUID.randomUUID().toString();

    @Builder.Default
    private Long timestamp = System.currentTimeMillis();

    private String branch;

    @JsonDeserialize(using = ChatMessageDeserializer.class)
    private ChatMessage message;

    @Builder.Default
    private Map<String, Object> metadata = new HashMap<>();

    @Builder.Default
    private boolean archived = false;

    @Override
    public EventTypeEnum getEventType() { return eventType; }

    public MessageEvent asArchived() {
        if (this.archived) return this;
        return MessageEvent.builder()
            .sessionId(this.getSessionId())
            .eventType(this.getEventType())
            .id(this.getId())
            .timestamp(this.getTimestamp())
            .branch(this.getBranch())
            .message(this.getMessage())
            .metadata(new HashMap<>(this.getMetadata()))
            .archived(true)
            .build();
    }

    /**
     * 返回替换了 metadata 的副本（id 等标识字段保持不变）。
     * 用于通知事件的一次性消费标记等原地元数据更新场景。
     */
    public MessageEvent withMetadata(Map<String, Object> newMetadata) {
        return MessageEvent.builder()
            .sessionId(this.getSessionId())
            .eventType(this.getEventType())
            .id(this.getId())
            .timestamp(this.getTimestamp())
            .branch(this.getBranch())
            .message(this.getMessage())
            .metadata(newMetadata != null ? newMetadata : new HashMap<>())
            .archived(this.isArchived())
            .build();
    }

    /**
     * 后台任务通知事件标记（metadata key）：notification=true 的 root 事件
     * 由 SessionMemoryInterceptor 注入下一轮上下文后标记 consumed（一次性消费）。
     */
    public static final String METADATA_NOTIFICATION = "notification";
    public static final String METADATA_CONSUMED = "consumed";

    /**
     * 压缩影子轮次标记（metadata key）：compactionShadow=true 表示该 synthetic
     * 事件由压缩策略生成（shadow-prompt 用户消息 + 摘要助手消息），是框架伪消息。
     */
    public static final String METADATA_COMPACTION_SHADOW = "compactionShadow";

    @JsonIgnore
    public boolean isSynthetic() {
        Object v = metadata.get(METADATA_SYNTHETIC);
        return v instanceof Boolean b && b;
    }

    @JsonIgnore
    public boolean isCompactionShadow() {
        Object v = metadata.get(METADATA_COMPACTION_SHADOW);
        return v instanceof Boolean b && b;
    }

    @JsonIgnore
    public boolean isRootEvent() { return this.branch == null; }

    @JsonIgnore
    public Role getMessageType() { return this.message != null ? this.message.getRole() : null; }

    @JsonIgnore
    public boolean hasToolCalls() {
        return this.message != null && this.message.hasToolCalls();
    }

    @JsonIgnore
    public boolean isUserMessage() {
        return message != null && message.getRole() == Role.USER;
    }

    @JsonIgnore
    public String getText() {
        return message != null ? message.getText() : null;
    }

    @JsonIgnore
    public boolean isAssistantMessage() {
        return message != null && message.getRole() == Role.ASSISTANT;
    }

    @JsonIgnore
    public boolean isToolResponse() {
        return message != null && message.getRole() == Role.TOOL;
    }

    @JsonIgnore
    public String getMessageId() {
        Object v = metadata.get("messageId");
        return v != null ? v.toString() : null;
    }

    @JsonIgnore
    public String getFinishReason() {
        return message != null ? message.finishReason() : null;
    }

    /** 思考内容（reasoning_content），无则为 null。 */
    @JsonIgnore
    public String getReasoningContent() {
        return message != null ? message.getReasoningContent() : null;
    }

    @JsonIgnore
    public List<ToolCallInfo> getToolCalls() {
        if (message != null && message.hasToolCalls()) {
            return message.getToolCalls().stream()
                    .map(tc -> new ToolCallInfo(tc.id(), tc.name(), tc.arguments()))
                    .toList();
        }
        return null;
    }

    @JsonIgnore
    public List<ToolResponseInfo> getResponses() {
        if (message != null && message.getRole() == Role.TOOL && message.getToolResults() != null) {
            return message.getToolResults().stream()
                    .map(r -> new ToolResponseInfo(r.id(), r.name(), r.content()))
                    .toList();
        }
        return null;
    }

    public static MessageEvent userMessage(String sessionId, String text) {
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(ChatMessage.user(text))
                .build();
    }

    public static MessageEvent assistantStop(String sessionId, String text) {
        return assistantStop(sessionId, text, null);
    }

    /** 带思考内容的 STOP 事件（中途停止时把已生成的思考一并落盘，供历史重建）。 */
    public static MessageEvent assistantStop(String sessionId, String text, String reasoningContent) {
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(ChatMessage.assistant(text, reasoningContent, null, "STOP"))
                .build();
    }

    public static MessageEvent toolResponse(String sessionId, List<ToolResponseInfo> responses) {
        List<ToolResult> results = responses.stream()
                .map(r -> new ToolResult(r.id(), r.name(), r.responseData()))
                .toList();
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(ChatMessage.toolResults(results))
                .build();
    }

    public static MessageEvent fromChatMessage(String sessionId, ChatMessage message) {
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(message)
                .build();
    }

    public record ToolCallInfo(String id, String name, String arguments) {}
    public record ToolResponseInfo(String id, String name, String responseData) {}
}
