package cn.bitloom.harness.loop;

import cn.bitloom.harness.llm.ChatChunk;
import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatModel;
import cn.bitloom.harness.llm.ChatOptions;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.llm.ToolCall;
import cn.bitloom.harness.llm.ToolResult;
import cn.bitloom.harness.tool.ToolCallDecision;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 智能体主循环 — 对标 dsh core/agent-loop：拦截器组装请求 → 流式 → 工具执行 → 续轮 → STOP 收尾。
 * <p>
 * 时序（与 {@link LoopInterceptor} 注释一致）：
 * <ol>
 *   <li>beforeRound（一次）</li>
 *   <li>循环：beforeModelCall → model.stream（边下发边聚合）→ afterModelCall；
 *       有 toolCalls 则执行工具（beforeToolCall/afterToolCall 包夹）并续轮，否则收尾</li>
 *   <li>afterRound（一次）</li>
 * </ol>
 * <p>
 * 线程模型：afterModelCall、工具执行、afterRound 一律切换到 boundedElastic，
 * 避免阻塞 Netty event loop（SSE 流所在线程）。上下文超长错误触发 reactive_compact：
 * 调用 reactiveCompactor 紧急压缩后整个 run 重试一次。
 */
@Slf4j
public class AgentLoop {

    private final ChatModel model;
    private final ToolExecutor toolExecutor;
    private final List<LoopInterceptor> interceptors;
    private final Consumer<String> reactiveCompactor;

    public AgentLoop(ChatModel model, ToolExecutor toolExecutor,
                     List<LoopInterceptor> interceptors, Consumer<String> reactiveCompactor) {
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.interceptors = interceptors != null
                ? interceptors.stream().sorted(java.util.Comparator.comparingInt(LoopInterceptor::order)).toList()
                : List.of();
        this.reactiveCompactor = reactiveCompactor;
    }

    /**
     * 运行主循环。
     *
     * @param messages 初始消息（system + 用户消息；历史装配由拦截器在 beforeModelCall 完成）
     * @param options  调用选项
     * @param ctx      循环上下文
     * @return 跨全部轮次的流式 chunk 流；STOP 轮完成后结束
     */
    public Flux<ChatChunk> run(List<ChatMessage> messages, ChatOptions options, LoopContext ctx) {
        return Flux.defer(() -> {
            for (LoopInterceptor i : interceptors) {
                i.beforeRound(ctx);
            }
            return runRound(new ArrayList<>(messages), options, ctx);
        }).onErrorResume(e -> {
            // 取消引发的异常不是真实错误，静默终止
            if (Exceptions.isCancel(e)) {
                return Flux.empty();
            }
            // reactive_compact：上下文超长被 API 拒绝时，紧急压缩后整个 run 重试一次
            if (isContextLengthError(e) && reactiveCompactor != null && ctx.sessionId() != null
                    && !Boolean.TRUE.equals(ctx.getParam(LoopContext.CTX_REACTIVE_RETRY))) {
                try {
                    reactiveCompactor.accept(ctx.sessionId());
                    ctx.put(LoopContext.CTX_REACTIVE_RETRY, Boolean.TRUE);
                    log.warn("[AgentLoop] 上下文超长，已执行紧急压缩，重试一次: sessionId={}", ctx.sessionId());
                    return run(messages, options, ctx);
                } catch (Exception compactEx) {
                    log.error("[AgentLoop] 紧急压缩失败，透传原始错误", compactEx);
                }
            }
            return Flux.error(e);
        });
    }

    private Flux<ChatChunk> runRound(List<ChatMessage> working, ChatOptions options, LoopContext ctx) {
        // 1. 拦截器组装请求
        ChatRequest request = ChatRequest.of(working,
                toolExecutor != null ? toolExecutor.toToolSpecs() : List.of(), options);
        for (LoopInterceptor i : interceptors) {
            ChatRequest modified = i.beforeModelCall(request, ctx);
            if (modified != null) {
                request = modified;
            }
        }
        ChatRequest finalRequest = request;

        // 2. 流式调用：边下发边聚合（不切线程，保证流式低延迟）
        ChatMessage assistant = ChatMessage.assistant(null);
        return model.stream(finalRequest.messages(), finalRequest.tools(), finalRequest.options())
                .doOnNext(chunk -> accumulate(assistant, chunk))
                .map(chunk -> applyChunkFilter(chunk))
                .concatWith(Mono.fromCallable(() -> afterStream(finalRequest, assistant, options, ctx))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(nextWorking -> nextWorking != null
                                ? runRound(nextWorking, options, ctx)
                                : Flux.empty()));
    }

    /**
     * 模型流结束后的收尾（boundedElastic 上执行）：
     * afterModelCall → 无工具则 afterRound 收尾；有工具则执行工具并追加消息。
     * <p>
     * 续轮基于装配后的请求消息列表（拦截器注入的 system 与历史保留），而非初始
     * working 列表 — 否则历史装配结果会在续轮丢失。
     *
     * @return 下一轮 working 消息列表；null 表示循环结束
     */
    private List<ChatMessage> afterStream(ChatRequest request, ChatMessage assistant,
                                          ChatOptions options, LoopContext ctx) {
        for (LoopInterceptor i : interceptors) {
            i.afterModelCall(request, assistant, ctx);
        }
        if (!assistant.hasToolCalls()) {
            for (LoopInterceptor i : interceptors) {
                i.afterRound(ctx);
            }
            return null;
        }
        List<ChatMessage> nextWorking = new ArrayList<>(request.messages());
        nextWorking.add(assistant.copy());
        List<ToolResult> results = executeTools(assistant, ctx);
        nextWorking.add(ChatMessage.toolResults(results));
        return nextWorking;
    }

    /** 工具执行阶段：beforeToolCall 链把关 → ToolExecutor 执行 → afterToolCall 链改写。 */
    private List<ToolResult> executeTools(ChatMessage assistant, LoopContext ctx) {
        ToolContext toolCtx = ctx.toToolContext();
        List<ToolResult> results = new ArrayList<>();
        for (ToolCall call : assistant.getToolCalls()) {
            String input = call.arguments() == null || call.arguments().isBlank()
                    ? "{}" : call.arguments();
            String currentInput = input;
            boolean blocked = false;
            String blockReason = null;
            for (LoopInterceptor i : interceptors) {
                ToolCallDecision decision = i.beforeToolCall(call.name(), currentInput, toolCtx);
                if (decision == null) {
                    continue;
                }
                if (!decision.proceed()) {
                    blocked = true;
                    blockReason = decision.blockReason();
                    break;
                }
                if (decision.input() != null) {
                    currentInput = decision.input();
                }
            }
            if (blocked) {
                results.add(new ToolResult(call.id(), call.name(),
                        cn.bitloom.harness.tool.ToolResult.toolDenied(call.name(), blockReason).toJson()));
                continue;
            }
            ToolResult executed = toolExecutor.execute(
                    new ToolCall(call.id(), call.name(), currentInput), toolCtx);
            String result = executed.content();
            for (LoopInterceptor i : interceptors) {
                String modified = i.afterToolCall(call.name(), result, toolCtx);
                if (modified != null) {
                    result = modified;
                }
            }
            results.add(new ToolResult(executed.id(), executed.name(), result));
        }
        return results;
    }

    /** 流式分片聚合到 assistant 消息（文本/思考增量拼接，终帧工具调用与 finishReason）。 */
    private void accumulate(ChatMessage assistant, ChatChunk chunk) {
        if (chunk.deltaText() != null) {
            assistant.appendText(chunk.deltaText());
        }
        if (chunk.deltaReasoning() != null) {
            assistant.appendReasoning(chunk.deltaReasoning());
        }
        if (chunk.toolCalls() != null && !chunk.toolCalls().isEmpty()) {
            for (ToolCall call : chunk.toolCalls()) {
                assistant.addToolCall(call);
            }
        }
        if (chunk.finishReason() != null) {
            assistant.setFinishReason(chunk.finishReason());
        }
    }

    /** 对文本增量应用拦截器 filterStreamChunk 链（返回 null 的拦截器不生效）。 */
    private ChatChunk applyChunkFilter(ChatChunk chunk) {
        if (chunk.deltaText() == null || interceptors.isEmpty()) {
            return chunk;
        }
        String current = chunk.deltaText();
        for (LoopInterceptor i : interceptors) {
            String filtered = i.filterStreamChunk(current);
            if (filtered != null) {
                current = filtered;
            }
        }
        if (current.equals(chunk.deltaText())) {
            return chunk;
        }
        return new ChatChunk(current, chunk.deltaReasoning(), chunk.toolCalls(),
                chunk.finishReason(), chunk.usage());
    }

    /** 识别上下文超长类错误（各 provider 消息措辞不同）。 */
    private boolean isContextLengthError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lower = msg.toLowerCase();
        return lower.contains("context length")
                || lower.contains("prompt is too long")
                || lower.contains("maximum context")
                || lower.contains("context window")
                || lower.contains("too many tokens")
                || lower.contains("input tokens exceed");
    }
}
