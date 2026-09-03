package cn.bitloom.harness.llm.json;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.llm.ToolCall;
import cn.bitloom.harness.llm.ToolResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ChatMessage 反序列化器：按 role 字段还原各角色的字段子集，
 * 兼容缺失字段与未知字段（向前容忍）。
 */
public class ChatMessageDeserializer extends StdDeserializer<ChatMessage> {

    public ChatMessageDeserializer() {
        super(ChatMessage.class);
    }

    @Override
    public ChatMessage deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.getCodec().readTree(p);
        String roleText = node.path("role").asText("user").toUpperCase();
        Role role;
        try {
            role = Role.valueOf(roleText);
        } catch (IllegalArgumentException e) {
            role = Role.USER;
        }
        String text = node.hasNonNull("text") ? node.get("text").asText() : null;
        String reasoning = node.hasNonNull("reasoningContent") ? node.get("reasoningContent").asText() : null;

        List<ToolCall> toolCalls = null;
        JsonNode tc = node.get("toolCalls");
        if (tc != null && tc.isArray()) {
            toolCalls = new ArrayList<>();
            for (JsonNode n : tc) {
                toolCalls.add(new ToolCall(
                        n.path("id").asText(null),
                        n.path("name").asText(null),
                        n.hasNonNull("arguments") ? n.get("arguments").asText() : "{}"));
            }
        }

        List<ToolResult> toolResults = null;
        JsonNode tr = node.get("toolResults");
        if (tr != null && tr.isArray()) {
            toolResults = new ArrayList<>();
            for (JsonNode n : tr) {
                toolResults.add(new ToolResult(
                        n.path("id").asText(null),
                        n.path("name").asText(null),
                        n.path("content").asText(null)));
            }
        }

        Map<String, Object> metadata = null;
        JsonNode md = node.get("metadata");
        if (md != null && md.isObject()) {
            Map<String, Object> plain = new HashMap<>();
            md.fields().forEachRemaining(e -> plain.put(e.getKey(),
                    e.getValue() instanceof JsonNode jn ? jsonToPlain(jn) : e.getValue()));
            metadata = plain;
        }

        ChatMessage message = new ChatMessage();
        message.set(role, text, reasoning, toolCalls, toolResults, metadata);
        return message;
    }

    private Object jsonToPlain(JsonNode n) {
        if (n.isBoolean()) {
            return n.asBoolean();
        }
        if (n.isInt()) {
            return n.asInt();
        }
        if (n.isLong()) {
            return n.asLong();
        }
        if (n.isDouble()) {
            return n.asDouble();
        }
        return n.asText();
    }
}
