package cn.bitloom.harness.llm;

import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenAI 兼容流式客户端 — reactor-netty HttpClient + 手动 SSE 解析，无 Spring 依赖。
 * <p>
 * 职责：
 * <ul>
 *   <li>消息/工具规格 → chat/completions 请求体（TOOL 消息展开为 N 条 wire 格式 tool 消息）</li>
 *   <li>SSE 分片解析：content/reasoning_content 增量直通；tool_calls 按 index 拼装，
 *       终帧一次性给出完整列表；usage 透传</li>
 *   <li>finishReason 归一：stop→STOP、tool_calls→TOOL_CALLS、length→LENGTH（其余原样大写）</li>
 *   <li>重试：连接错误 / 5xx 且尚未发出任何分片时退避重试（最多 3 次）</li>
 *   <li>错误体解包：非 2xx 把响应体并入异常消息（供上下文超长检测等上层逻辑识别）</li>
 * </ul>
 * 状态隔离：工具调用拼装状态在每次订阅内创建（Flux.defer），重试/重订阅不残留。
 */
@Slf4j
public class OpenAiCompatChatModel implements ChatModel {

    private final HttpClient httpClient;
    private final String completionsPath;
    private final String defaultModel;
    private final ChatOptions defaults;

    public OpenAiCompatChatModel(String baseUrl, String apiKey, String defaultModel,
                                 String completionsPath, ChatOptions defaults) {
        this.defaultModel = defaultModel;
        this.completionsPath = (completionsPath == null || completionsPath.isBlank())
                ? "/chat/completions" : completionsPath;
        this.defaults = defaults != null ? defaults : ChatOptions.of(defaultModel);
        this.httpClient = HttpClient.create()
                .baseUrl(baseUrl == null ? "" : baseUrl)
                .headers(h -> h.set("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                        .set("Content-Type", "application/json"));
    }

    @Override
    public String modelName() {
        return "openai-compat:" + defaultModel;
    }

    @Override
    public Flux<ChatChunk> stream(List<ChatMessage> messages, List<ToolSpec> tools, ChatOptions options) {
        ChatOptions opts = merge(options);
        ObjectNode body = buildRequestBody(messages, tools, opts);
        return Flux.defer(() -> {
            // 每次订阅（含重试）独立的拼装状态
            Map<Integer, String[]> toolCallParts = new TreeMap<>();   // index → [id, name, arguments]
            AtomicBoolean emitted = new AtomicBoolean(false);

            return httpClient.headers(h -> h.set("Accept", "text/event-stream"))
                    .post()
                    .uri(completionsPath)
                    .send(ByteBufFlux.fromString(Mono.just(body.toString())))
                    .response((resp, content) -> {
                        int status = resp.status().code();
                        if (status >= 400) {
                            return content.aggregate().asString()
                                    .defaultIfEmpty("")
                                    .flatMap(errorBody -> Mono.error(new HttpStatusException(status,
                                            "LLM API 错误 " + status + ": " + truncate(errorBody, 2000))));
                        }
                        return parseSseStream(content, toolCallParts, emitted);
                    })
                    // 连接错误/5xx 且尚未发出分片时重试（已发分片不重试，避免重复输出）
                    .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                            .filter(e -> isRetryable(e) && !emitted.get())
                            .doBeforeRetry(rs -> log.warn("[OpenAiCompatChatModel] 重试 #{}: {}",
                                    rs.totalRetries() + 1, rs.failure().getMessage()))
                            .onRetryExhaustedThrow((spec, rs) -> rs.failure()));
        });
    }

    // ===== SSE 流解析（跨块累积，按 \n\n 分割事件，提取 data 行） =====

    private Flux<ChatChunk> parseSseStream(ByteBufFlux content,
                                           Map<Integer, String[]> toolCallParts,
                                           AtomicBoolean emitted) {
        return Flux.defer(() -> {
            StringBuilder buffer = new StringBuilder();
            // 归一化换行（\r\n、\r → \n）后按空行切分 SSE 事件；末尾追加哨兵空行冲刷残留事件
            return content.asString()
                    .map(this::normalizeNewlines)
                    .concatWith(Mono.just("\n\n"))
                    .concatMap(chunk -> {
                        buffer.append(chunk);
                        List<ChatChunk> result = new ArrayList<>();
                        int idx;
                        while ((idx = buffer.indexOf("\n\n")) >= 0) {
                            String event = buffer.substring(0, idx);
                            buffer.delete(0, idx + 2);
                            String data = extractDataLine(event);
                            if (data != null && !"[DONE]".equals(data.trim())) {
                                result.addAll(parseChunk(data, toolCallParts, emitted));
                            }
                        }
                        return Flux.fromIterable(result);
                    });
        });
    }

    /** 将 SSE 流的 CRLF/CR 换行统一为 LF，避免仅按 \n\n 切分时漏掉 \r\n\r\n 边界。 */
    private String normalizeNewlines(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    /** 从单个 SSE 事件（多行）中提取 data: 行的内容。 */
    private String extractDataLine(String event) {
        for (String line : event.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("data:")) {
                return trimmed.substring("data:".length()).trim();
            }
        }
        return null;
    }

    // ===== 请求体构建 =====

    private ChatOptions merge(ChatOptions options) {
        if (options == null) {
            return defaults;
        }
        String model = options.model() != null ? options.model() : defaults.model();
        Double temperature = options.temperature() != null ? options.temperature() : defaults.temperature();
        Integer maxTokens = options.maxTokens() != null ? options.maxTokens() : defaults.maxTokens();
        Map<String, Object> extra = new HashMap<>();
        if (defaults.extraBody() != null) {
            extra.putAll(defaults.extraBody());
        }
        if (options.extraBody() != null) {
            extra.putAll(options.extraBody());
        }
        return new ChatOptions(model, temperature, maxTokens, extra.isEmpty() ? null : extra);
    }

    private ObjectNode buildRequestBody(List<ChatMessage> messages, List<ToolSpec> tools, ChatOptions opts) {
        ObjectNode root = JsonUtils.createObject();
        root.put("model", opts.model() != null ? opts.model() : defaultModel);
        root.put("stream", true);
        ObjectNode streamOptions = root.putObject("stream_options");
        streamOptions.put("include_usage", true);
        if (opts.temperature() != null) {
            root.put("temperature", opts.temperature());
        }
        if (opts.maxTokens() != null) {
            root.put("max_tokens", opts.maxTokens());
        }

        ArrayNode wireMessages = root.putArray("messages");
        for (ChatMessage message : messages) {
            switch (message.getRole()) {
                case SYSTEM, USER -> wireMessages.add(simpleMessage(message.getRole().name().toLowerCase(), message.getText()));
                case ASSISTANT -> wireMessages.add(assistantMessage(message));
                case TOOL -> {
                    // 一条聚合 TOOL 消息展开为 N 条 wire 格式 tool 消息
                    if (message.getToolResults() != null) {
                        for (ToolResult result : message.getToolResults()) {
                            ObjectNode n = JsonUtils.createObject();
                            n.put("role", "tool");
                            if (result.id() != null) {
                                n.put("tool_call_id", result.id());
                            }
                            n.put("content", result.content() != null ? result.content() : "");
                            wireMessages.add(n);
                        }
                    }
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode wireTools = root.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode t = JsonUtils.createObject();
                t.put("type", "function");
                ObjectNode fn = t.putObject("function");
                fn.put("name", spec.name());
                fn.put("description", spec.description() != null ? spec.description() : "");
                try {
                    fn.set("parameters", JsonUtils.mapper().readTree(
                            spec.inputSchema() != null ? spec.inputSchema() : "{\"type\":\"object\",\"properties\":{}}"));
                } catch (Exception e) {
                    fn.putObject("parameters");
                }
                wireTools.add(t);
            }
        }

        if (opts.extraBody() != null) {
            opts.extraBody().forEach(root::putPOJO);
        }
        return root;
    }

    private ObjectNode simpleMessage(String role, String text) {
        ObjectNode n = JsonUtils.createObject();
        n.put("role", role);
        n.put("content", text != null ? text : "");
        return n;
    }

    private ObjectNode assistantMessage(ChatMessage message) {
        ObjectNode n = JsonUtils.createObject();
        n.put("role", "assistant");
        n.put("content", message.getText() != null ? message.getText() : "");
        if (message.getReasoningContent() != null && !message.getReasoningContent().isBlank()) {
            n.put("reasoning_content", message.getReasoningContent());
        }
        if (message.hasToolCalls()) {
            ArrayNode calls = n.putArray("tool_calls");
            for (ToolCall call : message.getToolCalls()) {
                ObjectNode c = JsonUtils.createObject();
                if (call.id() != null) {
                    c.put("id", call.id());
                }
                c.put("type", "function");
                ObjectNode fn = c.putObject("function");
                fn.put("name", call.name() != null ? call.name() : "");
                fn.put("arguments", call.arguments() != null ? call.arguments() : "{}");
                calls.add(c);
            }
        }
        return n;
    }

    // ===== SSE 分片解析 =====

    private List<ChatChunk> parseChunk(String data, Map<Integer, String[]> toolCallParts, AtomicBoolean emitted) {
        JsonNode root;
        try {
            root = JsonUtils.parse(data);
        } catch (Exception e) {
            log.warn("[OpenAiCompatChatModel] 无法解析 SSE 数据块: {}", truncate(data, 200));
            return List.of();
        }

        JsonNode choice = root.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        List<ChatChunk> chunks = new ArrayList<>();

        String content = delta.hasNonNull("content") ? delta.get("content").asText() : null;
        String reasoning = delta.hasNonNull("reasoning_content") ? delta.get("reasoning_content").asText() : null;

        // 工具调用增量：按 index 拼装 id/name/arguments
        JsonNode wireCalls = delta.get("tool_calls");
        if (wireCalls != null && wireCalls.isArray()) {
            for (JsonNode wc : wireCalls) {
                int index = wc.path("index").asInt(0);
                String[] parts = toolCallParts.computeIfAbsent(index, k -> new String[]{null, null, ""});
                if (wc.hasNonNull("id") && wc.get("id").asText() != null) {
                    parts[0] = wc.get("id").asText();
                }
                JsonNode fn = wc.path("function");
                if (fn.hasNonNull("name")) {
                    parts[1] = fn.get("name").asText();
                }
                if (fn.hasNonNull("arguments")) {
                    parts[2] = parts[2] + fn.get("arguments").asText();
                }
            }
        }

        String finishReason = choice.hasNonNull("finish_reason")
                ? normalizeFinishReason(choice.get("finish_reason").asText()) : null;

        JsonNode usageNode = root.get("usage");
        Usage usage = usageNode != null && usageNode.isObject() ? new Usage(
                usageNode.hasNonNull("prompt_tokens") ? usageNode.get("prompt_tokens").asLong() : null,
                usageNode.hasNonNull("completion_tokens") ? usageNode.get("completion_tokens").asLong() : null,
                usageNode.hasNonNull("total_tokens") ? usageNode.get("total_tokens").asLong() : null) : null;

        if (content != null && !content.isEmpty()) {
            chunks.add(ChatChunk.text(content));
        }
        if (reasoning != null && !reasoning.isEmpty()) {
            chunks.add(ChatChunk.reasoning(reasoning));
        }

        if (finishReason != null) {
            List<ToolCall> assembled = null;
            if ("TOOL_CALLS".equals(finishReason) && !toolCallParts.isEmpty()) {
                assembled = toolCallParts.values().stream()
                        .map(p -> ToolCall.of(p[0], p[1], p[2])).toList();
            }
            chunks.add(ChatChunk.terminal(finishReason, assembled, usage));
        }

        if (!chunks.isEmpty()) {
            emitted.set(true);
        }
        return chunks;
    }

    private String normalizeFinishReason(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.toLowerCase()) {
            case "stop" -> "STOP";
            case "tool_calls", "function_call" -> "TOOL_CALLS";
            case "length" -> "LENGTH";
            case "content_filter" -> "CONTENT_FILTER";
            default -> raw.toUpperCase();
        };
    }

    private boolean isRetryable(Throwable e) {
        if (e instanceof HttpStatusException hse) {
            return hse.status >= 500;
        }
        return e instanceof java.io.IOException || e instanceof java.util.concurrent.TimeoutException;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 携带 HTTP 状态码的响应异常（区分 4xx/5xx 用于重试判定）。 */
    private static final class HttpStatusException extends IllegalStateException {
        final int status;

        HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
