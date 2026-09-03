package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 子智能体上下文注入拦截器（原 SubagentContextAdvisor 的 LoopInterceptor 形态），
 * 将子智能体描述注入到系统提示词中。
 *
 * <p>原 Advisor 语义为每用户消息注入一次；新 AgentLoop 中 beforeModelCall 在
 * 工具循环中每轮模型调用都会触发，因此用 ctx 标记（{@link #CTX_INJECTED}）
 * 保证一次 run 内只注入一次。
 */
@Slf4j
@Builder
public class SubagentContextInterceptor implements LoopInterceptor {

    /** ctx 标记：子智能体上下文已注入（每用户消息一次） */
    static final String CTX_INJECTED = "subagentContext.injected";

    private final AgentDefinitionManager definitionManager;

    private final AgentDefinition definition;

    @Override
    public String name() {
        return "SubagentContextInterceptor";
    }

    @Override
    public int order() {
        return 220;
    }

    @Override
    public ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
        if (definition == null || definitionManager == null
                || Boolean.TRUE.equals(ctx.getParam(CTX_INJECTED))) {
            return request;
        }

        try {
            String subagentDesc = buildSubagentDescriptions();
            if (subagentDesc == null || subagentDesc.isBlank()) {
                return request;
            }

            var messages = new ArrayList<>(request.messages());
            EnvironmentContextInterceptor.injectIntoSystemMessage(messages, subagentDesc);

            ctx.put(CTX_INJECTED, Boolean.TRUE);
            return request.withMessages(messages);
        } catch (Exception e) {
            log.warn("[SubagentContextInterceptor] 注入子智能体上下文失败", e);
            return request;
        }
    }

    private String buildSubagentDescriptions() {
        try {
            String descriptions = definition.subagents().stream()
                    .map(name -> {
                        AgentDefinition def = definitionManager.getDefinition(name);
                        return def != null ? "- " + name + ": " + def.description() : "";
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.joining("\n"));

            if (descriptions.isBlank()) {
                return null;
            }
            return "<subagents>\n" + descriptions + "\n</subagents>";
        } catch (Exception e) {
            log.debug("[SubagentContextInterceptor] 获取子智能体描述失败", e);
            return null;
        }
    }
}
