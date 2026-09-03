package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.llm.ToolCall;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 待办事项遗漏提醒拦截器（原 TodoReminderHook 的 LoopInterceptor 形态）。
 * <p>
 * 当 LLM 使用过 {@code TodoWrite} 工具后，若连续若干次 LLM 调用（停止原因为
 * {@code TOOL_CALLS}）都未再使用 {@code TodoWrite} 更新任务进度，则在下一次
 * 模型调用前注入一条 synthetic 用户消息，促使 LLM 主动更新待办事项状态。
 * <p>
 * 「连续 N 轮」按 LLM 调用次数累计，一轮对话内持续，直到某次调用使用了 TodoWrite
 * 清零，或一轮对话结束后整体重置。提醒通过 {@link #beforeModelCall} 注入为消息列表
 * 末尾的一条 synthetic 用户消息（注入后 pendingReminder 清零，避免重复注入），
 * 不改变 UI 渲染（工具卡片由拦截器链独立发布，不经过本拦截器）。
 * <p>
 * beforeModelCall / afterModelCall 保持原 Hook 语义：工具循环中每次模型调用都触发。
 */
@Slf4j
public class TodoReminderInterceptor implements LoopInterceptor {

    /** 连续多少次 LLM 调用未使用 TodoWrite 后触发提醒 */
    private static final int REMINDER_THRESHOLD = 3;

    private static final String TODO_TOOL_NAME = "TodoWrite";

    private static final String REMINDER_TEXT =
            "[系统提醒] 你已经连续 " + REMINDER_THRESHOLD
                    + " 轮工具调用未使用 TodoWrite 更新待办事项列表。"
                    + "请检查当前任务进度，并在需要时调用 TodoWrite 工具更新任务状态。";

    /** 提醒状态，按 sessionId+branch 隔离（每轮对话结束后移除） */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "TodoReminderInterceptor";
    }

    @Override
    public int order() {
        return 20; // 在 PermissionInterceptor(10) 之后执行，不干扰权限拦截
    }

    @Override
    public ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
        String key = stateKey(ctx);
        State state = key != null ? states.get(key) : null;
        if (state == null || !state.pendingReminder) {
            return request;
        }

        // 在下一次模型调用前注入提醒，一次注入后清除标记，避免重复注入
        state.pendingReminder = false;

        try {
            List<ChatMessage> messages = new ArrayList<>(request.messages());
            // synthetic 用户消息：注入 TodoWrite 更新提醒
            messages.add(ChatMessage.user(REMINDER_TEXT));

            log.info("[TodoReminderInterceptor] 在下一次模型调用前注入 TodoWrite 提醒（synthetic 用户消息）: key={}", key);
            return request.withMessages(messages);
        } catch (Exception e) {
            log.warn("[TodoReminderInterceptor] 注入提醒失败", e);
            return request;
        }
    }

    @Override
    public void afterModelCall(ChatRequest request, ChatMessage assistantMessage, LoopContext ctx) {
        if (assistantMessage == null || !"TOOL_CALLS".equals(assistantMessage.finishReason())) {
            return;
        }

        String key = stateKey(ctx);
        if (key == null) {
            return;
        }
        State state = states.computeIfAbsent(key, k -> new State());

        List<ToolCall> toolCalls = assistantMessage.getToolCalls();
        boolean usedTodo = toolCalls != null && toolCalls.stream()
                .anyMatch(tc -> TODO_TOOL_NAME.equals(tc.name()));

        if (usedTodo) {
            state.todoActive = true;
            state.streakWithoutTodo = 0;
            state.pendingReminder = false;
            return;
        }

        if (!state.todoActive) {
            return;
        }

        state.streakWithoutTodo++;
        if (state.streakWithoutTodo >= REMINDER_THRESHOLD) {
            state.pendingReminder = true;
            state.streakWithoutTodo = 0; // 提醒后重新计数，避免连续重复提醒
            log.debug("[TodoReminderInterceptor] 连续 {} 次调用未使用 TodoWrite，标记待注入提醒: key={}",
                    REMINDER_THRESHOLD, key);
        }
    }

    @Override
    public void afterRound(LoopContext ctx) {
        // 一轮对话结束，移除该 session 的状态，计数从头开始
        if (ctx == null) {
            return;
        }
        String key = stateKey(ctx.sessionId(), ctx.branch());
        if (key != null) {
            states.remove(key);
        }
    }

    /** 从 LoopContext 提取状态 key（sessionId+branch） */
    private String stateKey(LoopContext ctx) {
        if (ctx == null) {
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

    /** 提醒状态（按 sessionId+branch 隔离） */
    private static final class State {
        /** 是否已使用过 TodoWrite */
        boolean todoActive = false;
        /** 连续未使用 TodoWrite 的调用次数 */
        int streakWithoutTodo = 0;
        /** 是否待在下一次模型调用前注入提醒 */
        boolean pendingReminder = false;
    }
}
