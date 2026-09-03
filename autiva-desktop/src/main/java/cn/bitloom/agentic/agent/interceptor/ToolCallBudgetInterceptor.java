package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallDecision;
import cn.bitloom.harness.tool.ToolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用预算保护拦截器（原 ToolCallBudgetHook 的 LoopInterceptor 形态，
 * 对标 learn-claude-code 三层退出控制）。
 * <p>
 * Agent 循环的退出依赖 LLM 返回 STOP，无上限保护。本拦截器通过机制外挂方式补全
 * 保护，不触碰主循环：
 * <ul>
 *   <li>计数：每次实际工具执行（beforeToolCall）递增，按 sessionId+branch 隔离</li>
 *   <li>软提醒：达到预算 80% 时，在下一次模型调用前注入 synthetic 提醒，促使收敛</li>
 *   <li>硬停止：达到预算 100% 时，block 所有工具调用，LLM 收到错误说明后自然收尾</li>
 *   <li>出口：afterRound 清理状态</li>
 * </ul>
 * <p>
 * beforeModelCall 保持原 Hook 语义：工具循环中每次模型调用都触发（软提醒注入后
 * warned 标记防重复注入，无需 ctx 一次性标记）。
 */
@Slf4j
public class ToolCallBudgetInterceptor implements LoopInterceptor {

    /** 默认工具调用预算 */
    public static final int DEFAULT_MAX_TOOL_CALLS = 50;

    /** 软提醒阈值比例 */
    private static final double WARN_RATIO = 0.8;

    private final int maxToolCalls;

    /** 预算状态，按 sessionId+branch 隔离（Agent 实例可能跨 session/branch 复用） */
    private final Map<String, BudgetState> states = new ConcurrentHashMap<>();

    public ToolCallBudgetInterceptor() {
        this(DEFAULT_MAX_TOOL_CALLS);
    }

    public ToolCallBudgetInterceptor(int maxToolCalls) {
        this.maxToolCalls = Math.max(1, maxToolCalls);
    }

    @Override
    public String name() {
        return "ToolCallBudgetInterceptor";
    }

    @Override
    public int order() {
        return 5; // 在 PermissionInterceptor(10) 之前，预算耗尽时无需再走审批
    }

    @Override
    public ToolCallDecision beforeToolCall(String toolName, String input, ToolContext context) {
        String key = stateKey(context);
        if (key == null) {
            return ToolCallDecision.proceed(input);
        }

        BudgetState state = states.computeIfAbsent(key, k -> new BudgetState());
        int used = state.increment();

        if (used > maxToolCalls) {
            log.warn("[ToolCallBudgetInterceptor] 工具调用预算已耗尽（{}/{}），阻止工具: tool={}, key={}",
                    used - 1, maxToolCalls, toolName, key);
            return ToolCallDecision.block(
                    "工具调用预算已耗尽（" + (used - 1) + "/" + maxToolCalls
                            + "）。请立即基于已有信息给出最终回答，不要再尝试调用工具。");
        }
        return ToolCallDecision.proceed(input);
    }

    @Override
    public ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
        String key = stateKey(ctx);
        if (key == null) {
            return request;
        }
        BudgetState state = states.get(key);
        if (state == null || state.warned) {
            return request;
        }

        int warnThreshold = (int) Math.ceil(maxToolCalls * WARN_RATIO);
        if (state.count() < warnThreshold) {
            return request;
        }

        state.warned = true;
        String reminder = "[系统提醒] 已使用 " + state.count() + "/" + maxToolCalls
                + " 次工具调用预算，请尽快收敛任务并给出最终回答。";

        try {
            List<ChatMessage> messages = new ArrayList<>(request.messages());
            // synthetic 用户消息：注入预算软提醒，促使 LLM 收敛（一次注入后 warned 防重复）
            messages.add(ChatMessage.user(reminder));
            log.info("[ToolCallBudgetInterceptor] 注入预算提醒（synthetic 用户消息）: key={}, {}/{}",
                    key, state.count(), maxToolCalls);
            return request.withMessages(messages);
        } catch (Exception e) {
            log.warn("[ToolCallBudgetInterceptor] 注入预算提醒失败", e);
            return request;
        }
    }

    @Override
    public void beforeRound(LoopContext ctx) {
        if (ctx == null) {
            return;
        }
        // 轮次开始即重置：上一轮若经中断/异常结束（未触发 afterRound），
        // 残留计数会带入本轮导致误报预算耗尽
        String key = stateKey(ctx.sessionId(), ctx.branch());
        if (key != null) {
            states.remove(key);
        }
    }

    @Override
    public void afterRound(LoopContext ctx) {
        if (ctx == null) {
            return;
        }
        String key = stateKey(ctx.sessionId(), ctx.branch());
        if (key != null) {
            states.remove(key);
        }
    }

    private String stateKey(ToolContext context) {
        if (context == null) {
            return null;
        }
        Object sessionId = context.get("sessionId");
        Object branch = context.get("branch");
        if (sessionId instanceof String sid) {
            return stateKey(sid, branch instanceof String b ? b : null);
        }
        return null;
    }

    private String stateKey(LoopContext ctx) {
        if (ctx == null || ctx.sessionId() == null) {
            return null;
        }
        return stateKey(ctx.sessionId(), ctx.branch());
    }

    private String stateKey(String sessionId, String branch) {
        if (sessionId == null) {
            return null;
        }
        return branch != null ? sessionId + ":" + branch : sessionId + ":root";
    }

    /** 预算状态（按对话轮生命周期） */
    private static final class BudgetState {
        private int count = 0;
        private boolean warned = false;

        synchronized int increment() {
            return ++count;
        }

        synchronized int count() {
            return count;
        }
    }
}
