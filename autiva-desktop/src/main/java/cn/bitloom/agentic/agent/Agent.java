package cn.bitloom.agentic.agent;

import cn.bitloom.agentic.event.AbstractEvent;
import cn.bitloom.agentic.event.EventConverter;
import cn.bitloom.agentic.event.EventPublisher;
import cn.bitloom.agentic.event.MessageEvent;
import cn.bitloom.agentic.util.MessageUtil;
import cn.bitloom.harness.llm.ChatChunk;
import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatModel;
import cn.bitloom.harness.loop.AgentLoop;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.harness.tool.ToolCallback;
import cn.bitloom.harness.tool.ToolExecutor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jspecify.annotations.NonNull;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一智能体类，合并了原 AbstractAgent + MainAgent + SubagentExecutor 的功能。
 * 主智能体和子智能体都是 Agent 类的实例，区别仅在 AgentDefinition 的配置。
 * <p>
 * Agent 实例本身只持有不可变的配置，所有 per-session 的可变数据都放在 Session 里。
 * 一个 Agent 实例可以同时服务多个用户和会话。
 * <p>
 * 内部由 {@link AgentLoop} 驱动：拦截器（LoopInterceptor）统一负责历史装配、
 * 持久化、审批把关、UI 卡片事件等横切逻辑。只能通过 Builder 创建。
 */
@Slf4j
public class Agent {

    @Getter
    private final @NonNull String name;
    @Getter
    private final @NonNull AgentDefinition definition;
    @Getter
    private final @NonNull ChatModel model;
    @Getter
    private final @NonNull ToolExecutor toolExecutor;
    @Getter
    private final @NonNull List<LoopInterceptor> interceptors;
    private final String systemPrompt;
    /**
     * 紧急上下文压缩器（reactive_compact）：sessionId -> 强制压缩，可为 null。
     * 重试逻辑内建在 {@link AgentLoop}（CTX_REACTIVE_RETRY 标记）。
     */
    private final Consumer<String> reactiveCompactor;

    private Agent(@NonNull String name, @NonNull AgentDefinition definition, @NonNull ChatModel model,
                  @NonNull ToolExecutor toolExecutor, @NonNull List<LoopInterceptor> interceptors,
                  String systemPrompt, Consumer<String> reactiveCompactor) {
        this.name = name;
        this.definition = definition;
        this.model = model;
        this.toolExecutor = toolExecutor;
        this.interceptors = interceptors;
        this.systemPrompt = systemPrompt;
        this.reactiveCompactor = reactiveCompactor;
    }

    /**
     * 流式调用 LLM，返回事件流。
     * <p>
     * 入参 MessageEvent + LoopContext，出参 Flux&lt;AbstractEvent&gt;。
     * <ul>
     *   <li>Flux.create 内部创建 sink 作为事件汇聚点</li>
     *   <li>sink::next 包装为 EventPublisher 注入 ctx，经 ToolContext 传递给工具层</li>
     *   <li>AgentLoop 的 ChatChunk 流在此适配为 MessageEvent：增量 chunk 直通（无 finishReason），
     *       终帧携带聚合后的完整消息（text/reasoning/toolCalls + finishReason）</li>
     *   <li>工具卡片等 UI 事件由拦截器经 EventPublisher 推入同一 sink</li>
     * </ul>
     * Agent 实例不绑定 session，可复用（不同 session 传不同 ctx）。
     * <p>
     * 错误处理：onErrorResume 将异常转换为兜底 MessageEvent，避免错误裸抛到订阅层。
     */
    public Flux<AbstractEvent> runStream(MessageEvent inputEvent, LoopContext ctx) {
        String sessionId = ctx.sessionId();
        String branch = ctx.branch();
        AgentLoop loop = new AgentLoop(model, toolExecutor, interceptors, reactiveCompactor);
        return Flux.<AbstractEvent>create(sink -> {
            // 把 sink::next 包装为 EventPublisher，自动给 MessageEvent 设置 branch
            // （工具事件和 LLM 流事件统一打标，便于 UI 路由和历史过滤）
            EventPublisher runtimePublisher = event -> {
                if (branch != null && event instanceof MessageEvent me && me.getBranch() == null) {
                    me.setBranch(branch);
                }
                sink.next(event);
            };
            ctx.put("eventSink", runtimePublisher);

            List<ChatMessage> messages = new ArrayList<>();
            if (StringUtils.isNotBlank(this.systemPrompt)) {
                messages.add(ChatMessage.system(this.systemPrompt));
            }
            messages.add(EventConverter.toChatMessage(inputEvent));

            // 边下发边聚合：终帧事件需携带完整聚合消息
            ChatMessage aggregated = ChatMessage.assistant(null);
            Disposable inner = loop.run(messages, null, ctx)
                    .doOnNext(chunk -> accumulate(aggregated, chunk))
                    .mapNotNull(chunk -> toEvent(sessionId, chunk, aggregated))
                    .subscribe(sink::next, sink::error, sink::complete);
            // 级联取消：下游 dispose 时同步取消内部 LLM 流订阅，
            // 否则模型调用与工具循环会在内部线程继续执行（pause 失效的根因）
            sink.onCancel(inner);
            sink.onDispose(inner);
        }).onErrorResume(e -> {
            // 取消引发的异常不是真实错误，静默终止即可
            if (Exceptions.isCancel(e)) {
                return Flux.empty();
            }
            log.error("LLM stream error", e);
            MessageEvent fallbackEvent = MessageEvent.fromChatMessage(sessionId, MessageUtil.buildFallbackMessage());
            if (branch != null && fallbackEvent.getBranch() == null) {
                fallbackEvent.setBranch(branch);
            }
            return Flux.just(fallbackEvent);
        });
    }

    /** 流式分片聚合（与 AgentLoop 内部聚合一致，供终帧事件携带完整消息）。 */
    private void accumulate(ChatMessage aggregated, ChatChunk chunk) {
        if (chunk.deltaText() != null) {
            aggregated.appendText(chunk.deltaText());
        }
        if (chunk.deltaReasoning() != null) {
            aggregated.appendReasoning(chunk.deltaReasoning());
        }
        if (chunk.finishReason() != null) {
            aggregated.setFinishReason(chunk.finishReason());
        }
    }

    /**
     * ChatChunk → MessageEvent 适配。
     * <ul>
     *   <li>增量 chunk：无 finishReason 的 assistant 消息（UI 按增量累积渲染）</li>
     *   <li>终帧：聚合完整消息（text/reasoning/toolCalls + finishReason）</li>
     * </ul>
     */
    private MessageEvent toEvent(String sessionId, ChatChunk chunk, ChatMessage aggregated) {
        if (chunk.isTerminal()) {
            ChatMessage message = ChatMessage.assistant(
                    aggregated.getText(), aggregated.getReasoningContent(),
                    chunk.toolCalls(), chunk.finishReason());
            return MessageEvent.fromChatMessage(sessionId, message);
        }
        if (chunk.deltaText() == null && chunk.deltaReasoning() == null) {
            return null;
        }
        // 文本增量直通（UI 逐段 append）；思考内容为覆盖语义，需携带累计全文
        // （UI 每次整体替换，正文开启后残留的累计思考由 currentAssistantCard==null 守卫忽略）
        return MessageEvent.fromChatMessage(sessionId,
                ChatMessage.assistant(chunk.deltaText(), aggregated.getReasoningContent(), null, null));
    }

    /**
     * 阻塞调用 LLM，返回最终 MessageEvent。
     * 仅返回最后一条非空 assistant 消息事件。
     */
    public MessageEvent runBlock(MessageEvent inputEvent, LoopContext ctx) {
        return runStream(inputEvent, ctx)
                .filter(e -> e instanceof MessageEvent)
                .cast(MessageEvent.class)
                .blockLast();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String name;
        private AgentDefinition definition;
        private String systemPrompt;
        private ChatModel model;
        private List<ToolCallback> tools = new ArrayList<>();
        private List<LoopInterceptor> interceptors = new ArrayList<>();
        private Consumer<String> reactiveCompactor;

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder definition(AgentDefinition definition) {
            this.definition = definition;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
            return this;
        }

        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder tools(List<ToolCallback> tools) {
            this.tools = tools;
            return this;
        }

        public Builder interceptors(List<LoopInterceptor> interceptors) {
            this.interceptors = interceptors;
            return this;
        }

        /**
         * 紧急上下文压缩器（reactive_compact）：上下文超长被 API 拒绝时调用，强制压缩后重试一次。
         * 典型实现：{@code sid -> sessionManager.compact(sid, r -> true, stagedStrategy)}
         */
        public Builder reactiveCompactor(Consumer<String> reactiveCompactor) {
            this.reactiveCompactor = reactiveCompactor;
            return this;
        }

        public Agent build() {
            ToolExecutor toolExecutor = new ToolExecutor(this.tools != null ? this.tools : List.of());
            return new Agent(name, definition, model, toolExecutor,
                    interceptors != null ? interceptors : List.of(),
                    systemPrompt, reactiveCompactor);
        }
    }
}
