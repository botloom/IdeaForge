package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境信息注入拦截器（原 EnvironmentContextAdvisor 的 LoopInterceptor 形态），
 * 将 OS 和时间信息注入到系统提示词中。
 *
 * <p>原 Advisor 语义为每用户消息注入一次；新 AgentLoop 中 beforeModelCall 在
 * 工具循环中每轮模型调用都会触发，因此用 ctx 标记（{@link #CTX_INJECTED}）
 * 保证一次 run 内只注入一次。
 */
@Slf4j
@Builder
public class EnvironmentContextInterceptor implements LoopInterceptor {

    /** ctx 标记：环境信息已注入（每用户消息一次） */
    static final String CTX_INJECTED = "environment.injected";

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String name() {
        return "EnvironmentInterceptor";
    }

    @Override
    public int order() {
        return 200;
    }

    @Override
    public ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
        if (Boolean.TRUE.equals(ctx.getParam(CTX_INJECTED))) {
            return request;
        }
        try {
            String environmentText = buildEnvironmentText();
            if (environmentText.isBlank()) {
                return request;
            }

            List<ChatMessage> messages = new ArrayList<>(request.messages());
            injectIntoSystemMessage(messages, environmentText);

            ctx.put(CTX_INJECTED, Boolean.TRUE);
            return request.withMessages(messages);
        } catch (Exception e) {
            log.warn("[EnvironmentInterceptor] 注入环境信息失败", e);
            return request;
        }
    }

    /**
     * 追加文本到第一条 system 消息；无 system 消息时前置一条新的 system 消息。
     */
    static void injectIntoSystemMessage(List<ChatMessage> messages, String text) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message.getRole() == Role.SYSTEM) {
                String augmentedText = message.getText() + "\n\n" + text;
                messages.set(i, ChatMessage.system(augmentedText));
                return;
            }
        }
        messages.addFirst(ChatMessage.system(text));
    }

    private String buildEnvironmentText() {
        StringBuilder sb = new StringBuilder();
        sb.append("<environment>");
        sb.append(System.lineSeparator());

        sb.append("- 语言环境：中文");
        sb.append(System.lineSeparator());

        String osName = System.getProperty("os.name", "unknown");
        String osVersion = System.getProperty("os.version", "");
        String osArch = System.getProperty("os.arch", "unknown");
        sb.append("- OS: ").append(osName);
        if (!osVersion.isEmpty()) {
            sb.append(" ").append(osVersion);
        }
        sb.append(" (").append(osArch).append(")");

        sb.append(System.lineSeparator());
        ZoneId zoneId = ZoneId.systemDefault();
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        sb.append("- Time: ").append(now.format(TIME_FORMATTER));
        sb.append(" (UTC").append(now.getOffset().getId()).append(")");

        sb.append(System.lineSeparator());
        sb.append("</environment>");
        return sb.toString();
    }
}
