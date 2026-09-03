package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;

/**
 * 技能上下文注入拦截器（原 SkillContextAdvisor 的 LoopInterceptor 形态），
 * 将技能描述注入到系统提示词中。
 *
 * <p>原 Advisor 语义为每用户消息注入一次；新 AgentLoop 中 beforeModelCall 在
 * 工具循环中每轮模型调用都会触发，因此用 ctx 标记（{@link #CTX_INJECTED}）
 * 保证一次 run 内只注入一次。
 */
@Slf4j
@Builder
public class SkillContextInterceptor implements LoopInterceptor {

    /** ctx 标记：技能上下文已注入（每用户消息一次） */
    static final String CTX_INJECTED = "skillContext.injected";

    private final SkillManager skillManager;

    @Override
    public String name() {
        return "SkillContextInterceptor";
    }

    @Override
    public int order() {
        return 210;
    }

    @Override
    public ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
        if (skillManager == null || Boolean.TRUE.equals(ctx.getParam(CTX_INJECTED))) {
            return request;
        }

        try {
            String skillDesc = buildSkillDescriptions();
            if (skillDesc == null || skillDesc.isBlank()) {
                return request;
            }

            var messages = new ArrayList<>(request.messages());
            EnvironmentContextInterceptor.injectIntoSystemMessage(messages, skillDesc);

            ctx.put(CTX_INJECTED, Boolean.TRUE);
            return request.withMessages(messages);
        } catch (Exception e) {
            log.warn("[SkillContextInterceptor] 注入技能上下文失败", e);
            return request;
        }
    }

    private String buildSkillDescriptions() {
        try {
            String descriptions = skillManager.getDescription();
            if (descriptions == null || descriptions.isBlank()) {
                return null;
            }
            return "<skills>\n" + descriptions + "\n</skills>";
        } catch (Exception e) {
            log.debug("[SkillContextInterceptor] 获取技能描述失败", e);
            return null;
        }
    }
}
