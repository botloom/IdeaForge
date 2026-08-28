package cn.bitloom.agentic.event;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

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

    @JsonDeserialize(using = MessageDeserializer.class)
    private Message message;

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
     * 由 SessionMemoryAdvisor 注入下一轮上下文后标记 consumed（一次性消费）。
     */
    public static final String METADATA_NOTIFICATION = "notification";
    public static final String METADATA_CONSUMED = "consumed";

    /**
     * 压缩影子轮次标记（metadata key）：compactionShadow=true 表示该 synthetic
     * 事件由压缩策略生成（shadow-prompt 用户消息 + 摘要助手消息），是框架伪消息。
     * 与续轮 / 通知类的 synthetic 区分：后者是真实发生的系统注入，历史加载时应
     * 渲染为 NotificationCard 并充当轮次边界；前者应跳过不渲染。
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
    public MessageType getMessageType() { return this.message != null ? this.message.getMessageType() : null; }

    @JsonIgnore
    public boolean hasToolCalls() {
        return this.message instanceof AssistantMessage am && am.hasToolCalls();
    }

    @JsonIgnore
    public boolean isUserMessage() {
        return message != null && message.getMessageType() == MessageType.USER;
    }

    @JsonIgnore
    public String getText() {
        return message != null ? message.getText() : null;
    }

    @JsonIgnore
    public boolean isAssistantMessage() {
        return message != null && message.getMessageType() == MessageType.ASSISTANT;
    }

    @JsonIgnore
    public boolean isToolResponse() {
        return message != null && message.getMessageType() == MessageType.TOOL;
    }

    @JsonIgnore
    public String getMessageId() {
        Object v = metadata.get("messageId");
        return v != null ? v.toString() : null;
    }

    @JsonIgnore
    public String getFinishReason() {
        if (message instanceof AssistantMessage am) {
            Object v = am.getMetadata().get("finishReason");
            return v != null ? v.toString() : null;
        }
        return null;
    }

    /**
     * 思考内容（reasoning_content）。流式 chunk 与最终消息的 AssistantMessage metadata
     * 中由 OpenAI Starter 以 "reasoningContent" key 承载；无则为 null。
     */
    @JsonIgnore
    public String getReasoningContent() {
        if (message instanceof AssistantMessage am) {
            Object v = am.getMetadata().get("reasoningContent");
            return v != null ? v.toString() : null;
        }
        return null;
    }

    @JsonIgnore
    public List<ToolCallInfo> getToolCalls() {
        if (message instanceof AssistantMessage am && am.hasToolCalls()) {
            return am.getToolCalls().stream()
                    .map(tc -> new ToolCallInfo(tc.id(), tc.name(), tc.arguments()))
                    .toList();
        }
        return null;
    }

    @JsonIgnore
    public List<ToolResponseInfo> getResponses() {
        if (message instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream()
                    .map(r -> new ToolResponseInfo(r.id(), r.name(), r.responseData()))
                    .toList();
        }
        return null;
    }

    public static MessageEvent userMessage(String sessionId, String text) {
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(UserMessage.builder().text(text).build())
                .build();
    }

    public static MessageEvent assistantStop(String sessionId, String text) {
        return assistantStop(sessionId, text, null);
    }

    /** 带思考内容的 STOP 事件（中途停止时把已生成的思考一并落盘，供历史重建）。 */
    public static MessageEvent assistantStop(String sessionId, String text, String reasoningContent) {
        Map<String, Object> props = new HashMap<>();
        props.put("finishReason", "STOP");
        if (reasoningContent != null && !reasoningContent.isBlank()) {
            props.put("reasoningContent", reasoningContent);
        }
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(AssistantMessage.builder()
                        .content(text)
                        .properties(props)
                        .build())
                .build();
    }

    public static MessageEvent toolResponse(String sessionId, List<ToolResponseInfo> responses) {
        List<ToolResponseMessage.ToolResponse> trList = responses.stream()
                .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.responseData()))
                .toList();
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(ToolResponseMessage.builder().responses(trList).build())
                .build();
    }

    public record ToolCallInfo(String id, String name, String arguments) {}
    public record ToolResponseInfo(String id, String name, String responseData) {}
}
