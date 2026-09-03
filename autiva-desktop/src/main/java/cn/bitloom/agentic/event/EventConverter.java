package cn.bitloom.agentic.event;

import cn.bitloom.harness.llm.ChatMessage;

public class EventConverter {

    public static MessageEvent fromChatMessage(String sessionId, ChatMessage message) {
        return MessageEvent.builder()
                .sessionId(sessionId)
                .message(message)
                .build();
    }

    /** 提取事件携带的消息（无消息时返回空 user 消息，保持旧 toUserMessage 语义）。 */
    public static ChatMessage toChatMessage(MessageEvent event) {
        if (event.getMessage() != null) {
            return event.getMessage();
        }
        return ChatMessage.user("");
    }
}
