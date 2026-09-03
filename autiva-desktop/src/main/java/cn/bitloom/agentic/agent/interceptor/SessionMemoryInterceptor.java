package cn.bitloom.agentic.agent.interceptor;

import cn.bitloom.agentic.event.AbstractEvent;
import cn.bitloom.agentic.event.MessageEvent;
import cn.bitloom.agentic.session.CreateSessionRequest;
import cn.bitloom.agentic.session.EventFilter;
import cn.bitloom.agentic.session.ISessionManager;
import cn.bitloom.agentic.session.MessageFilter;
import cn.bitloom.agentic.session.Session;
import cn.bitloom.agentic.session.SessionEventRequestIdGenerator;
import cn.bitloom.agentic.session.SessionEventResponseIdGenerator;
import cn.bitloom.agentic.session.compaction.CompactionStrategy;
import cn.bitloom.agentic.session.compaction.CompactionTrigger;
import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.ChatRequest;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.loop.LoopContext;
import cn.bitloom.harness.loop.LoopInterceptor;
import cn.bitloom.util.Assert;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 会话记忆拦截器（原 SessionMemoryAdvisor 的 LoopInterceptor 形态），使用
 * {@link ISessionManager} 管理对话历史，支持可选的上下文压缩。
 *
 * <p>
 * 每次 AgentLoop 运行：
 * <ol>
 * <li>首轮 beforeModelCall：从会话检索事件历史装配到请求消息，持久化当前用户消息；
 * 续轮 beforeModelCall：持久化上一轮新增的 tool 结果消息。</li>
 * <li>afterModelCall：持久化每轮聚合的 assistant 消息（TOOL_CALLS / STOP 均在此落盘）。</li>
 * <li>afterRound（run 结束）：若配置的触发器触发，执行上下文压缩。</li>
 * </ol>
 *
 * <p>
 * <strong>branch 可见性：</strong>子智能体（ctx.branch != null）仅装配自己 branch 内
 * 的事件，持久化时写入 branch；主智能体（branch == null）仅可见 root 事件。
 *
 * <p>
 * <strong>reactive_compact 重试：</strong>AgentLoop 紧急压缩后重试时
 * （CTX_REACTIVE_RETRY）重新装配历史（压缩后上下文已变小），但跳过用户消息的
 * 重复持久化。
 *
 * <p>
 * <strong>并发压缩安全：</strong>压缩使用乐观 compare-and-swap 写入通过
 * {@code SessionRepository.compactEvents}，同会话并发压缩只有一个写入者成功。
 */
public final class SessionMemoryInterceptor implements LoopInterceptor {

	private static final Logger logger = LoggerFactory.getLogger(SessionMemoryInterceptor.class);

	/** ctx 标记：最近一次装配后的消息列表大小（0 = 未装配；请求小于此值 = reactive 重试） */
	static final String CTX_ASSEMBLED_SIZE = "sessionMemory.assembledSize";
	/** ctx 标记：已持久化的消息数（请求消息列表前缀长度） */
	static final String CTX_PERSISTED_COUNT = "sessionMemory.persistedCount";

	private final ISessionManager sessionService;

	private final String defaultUserId;

	private final EventFilter eventFilter;

	private final MessageFilter messageFilter;

	private final SessionEventRequestIdGenerator requestEventIdGenerator;

	private final SessionEventResponseIdGenerator responseEventIdGenerator;

	@Nullable private final CompactionTrigger compactionTrigger;

	@Nullable private final CompactionStrategy compactionStrategy;

	private SessionMemoryInterceptor(ISessionManager sessionService, String defaultUserId, EventFilter eventFilter,
			MessageFilter messageFilter, SessionEventRequestIdGenerator requestEventIdGenerator,
			SessionEventResponseIdGenerator responseEventIdGenerator,
			@Nullable CompactionTrigger compactionTrigger, @Nullable CompactionStrategy compactionStrategy) {
		this.sessionService = sessionService;
		this.defaultUserId = defaultUserId;
		this.eventFilter = eventFilter;
		this.messageFilter = messageFilter;
		this.requestEventIdGenerator = requestEventIdGenerator;
		this.responseEventIdGenerator = responseEventIdGenerator;
		this.compactionTrigger = compactionTrigger;
		this.compactionStrategy = compactionStrategy;
	}

	/**
	 * 高 order 值：beforeModelCall 在其他拦截器（技能/环境/子代理上下文注入 system
	 * 消息）之后运行，装配历史时把已注入的 system 消息一并归位到列表头部。
	 */
	@Override
	public int order() {
		return 1000;
	}

	@Override
	public ChatRequest beforeModelCall(ChatRequest request, LoopContext ctx) {
		String sessionId = ctx.sessionId();
		if (sessionId == null) {
			// 无会话上下文（纯一次性调用），不做装配与持久化
			return request;
		}

		int assembledSize = assembledSize(ctx);
		boolean assembled = assembledSize > 0;
		// reactive_compact 重试：AgentLoop 用原始消息重发（小于装配后大小），需重新装配
		// （压缩后历史已变），但跳过用户消息的重复持久化
		boolean reactiveRetry = Boolean.TRUE.equals(ctx.getParam(LoopContext.CTX_REACTIVE_RETRY));

		if (!assembled || request.messages().size() < assembledSize) {
			return assemble(request, ctx, sessionId, reactiveRetry);
		}
		return persistIncrementalToolMessages(request, ctx, sessionId);
	}

	/** 首轮（或 reactive 重试）：装配历史 + 持久化当前用户消息。 */
	private ChatRequest assemble(ChatRequest request, LoopContext ctx, String sessionId, boolean reactiveRetry) {
		// 1. 查找或创建会话
		Session session = this.sessionService.getById(sessionId);
		if (session == null) {
			String userId = ctx.userId() != null && !ctx.userId().isBlank() ? ctx.userId() : this.defaultUserId;
			session = this.sessionService.create(CreateSessionRequest.builder().id(sessionId).userId(userId).build());
		}
		else {
			// 显式指定 userId 时强制所有权校验
			if (ctx.userId() != null && !ctx.userId().isBlank() && !ctx.userId().equals(session.userId())) {
				throw new IllegalStateException(
						"Session '" + sessionId + "' does not belong to user '" + ctx.userId() + "'. Access denied.");
			}
		}

		// 2. 检索历史（branch 可见性 + 排除已归档事件）
		EventFilter filter = this.eventFilter.merge(EventFilter.active());
		if (ctx.branch() != null) {
			filter = filter.merge(EventFilter.forBranch(ctx.branch()));
		}
		List<AbstractEvent> events = this.sessionService.getEvents(sessionId, filter);

		// 后台任务通知消费（push 模型）：注入本轮上下文后立即标记 consumed（一次性）
		consumeNotifications(events, sessionId);

		List<ChatMessage> history = events.stream()
			.filter(e -> e instanceof MessageEvent me && !isConsumedNotification(me))
			.map(e -> ((MessageEvent) e).getMessage())
			.filter(Objects::nonNull)
			.toList();

		// 3. 当前请求消息拆分：system 前置 + 历史 + 其余
		List<ChatMessage> systemMessages = request.messages().stream()
			.filter(m -> m.getRole() == Role.SYSTEM)
			.toList();
		List<ChatMessage> current = request.messages().stream()
			.filter(m -> m.getRole() != Role.SYSTEM)
			.toList();

		// 4. 持久化当前用户（或工具响应）消息 — reactive 重试跳过（首次已落盘）
		if (!reactiveRetry) {
			ChatMessage lastUserOrTool = findLastUserOrTool(current);
			if (lastUserOrTool != null && shouldPersist(lastUserOrTool, sessionId)) {
				appendEvent(sessionId, ctx, lastUserOrTool, this.requestEventIdGenerator.generate(ctx, lastUserOrTool));
			}
		}

		List<ChatMessage> combined = new ArrayList<>(systemMessages);
		combined.addAll(history);
		combined.addAll(current);

		ctx.put(CTX_ASSEMBLED_SIZE, combined.size());
		ctx.put(CTX_PERSISTED_COUNT, combined.size());
		return request.withMessages(combined);
	}

	/** 续轮：持久化上一轮新增的 tool 结果消息（assistant 已在 afterModelCall 落盘）。 */
	private ChatRequest persistIncrementalToolMessages(ChatRequest request, LoopContext ctx, String sessionId) {
		int persistedCount = persistedCount(ctx);
		List<ChatMessage> messages = request.messages();
		for (int i = persistedCount; i < messages.size(); i++) {
			ChatMessage message = messages.get(i);
			if (message.getRole() != Role.TOOL) {
				continue;
			}
			if (shouldPersist(message, sessionId)) {
				appendEvent(sessionId, ctx, message, this.responseEventIdGenerator.generate(ctx, message));
			}
		}
		if (messages.size() != persistedCount) {
			ctx.put(CTX_PERSISTED_COUNT, messages.size());
		}
		return request;
	}

	@Override
	public void afterModelCall(ChatRequest request, ChatMessage assistantMessage, LoopContext ctx) {
		String sessionId = ctx.sessionId();
		if (sessionId == null || assistantMessage == null) {
			return;
		}
		// 每轮聚合的 assistant 消息（含 TOOL_CALLS 轮与 STOP 终轮）统一在此持久化
		if (shouldPersist(assistantMessage, sessionId)) {
			appendEvent(sessionId, ctx, assistantMessage,
					this.responseEventIdGenerator.generate(ctx, assistantMessage));
		}
	}

	@Override
	public void afterRound(LoopContext ctx) {
		// 同步压缩（如果配置）— 完整轮次（用户 + 助手 + 工具）已写入，无竞争
		if (this.compactionTrigger != null && this.compactionStrategy != null && ctx.sessionId() != null) {
			this.sessionService.compact(ctx.sessionId(), this.compactionTrigger, this.compactionStrategy);
		}
	}

	// ===== 内部 =====

	private void appendEvent(String sessionId, LoopContext ctx, ChatMessage message, String eventId) {
		MessageEvent.MessageEventBuilder builder = MessageEvent.builder()
			.id(eventId)
			.sessionId(sessionId)
			.message(message);
		if (ctx.branch() != null) {
			builder.branch(ctx.branch());
		}
		this.sessionService.appendEvent(builder.build());
	}

	private static ChatMessage findLastUserOrTool(List<ChatMessage> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			ChatMessage m = messages.get(i);
			if (m.getRole() == Role.USER || m.getRole() == Role.TOOL) {
				return m;
			}
		}
		return null;
	}

	private static int assembledSize(LoopContext ctx) {
		Object v = ctx.getParam(CTX_ASSEMBLED_SIZE);
		return v instanceof Number n ? n.intValue() : 0;
	}

	private static int persistedCount(LoopContext ctx) {
		Object v = ctx.getParam(CTX_PERSISTED_COUNT);
		return v instanceof Number n ? n.intValue() : 0;
	}

	/**
	 * 后台任务通知消费：将本轮注入上下文的未消费 notification 事件标记 consumed。
	 * 标记失败仅记录（下轮可能重复注入一次，可接受，不阻塞主流程）。
	 */
	private void consumeNotifications(List<AbstractEvent> events, String sessionId) {
		for (AbstractEvent event : events) {
			if (event instanceof MessageEvent me && isNotification(me) && !isConsumedNotification(me)) {
				try {
					this.sessionService.updateEventMetadata(sessionId, me.getId(),
							MessageEvent.METADATA_CONSUMED, Boolean.TRUE);
				}
				catch (Exception ex) {
					logger.debug("标记通知 consumed 失败: sessionId={}, eventId={}: {}", sessionId, me.getId(),
							ex.getMessage());
				}
			}
		}
	}

	private boolean isNotification(MessageEvent event) {
		Object v = event.getMetadata() != null ? event.getMetadata().get(MessageEvent.METADATA_NOTIFICATION) : null;
		return v instanceof Boolean b && b;
	}

	private boolean isConsumedNotification(MessageEvent event) {
		if (!isNotification(event)) {
			return false;
		}
		Object v = event.getMetadata() != null ? event.getMetadata().get(MessageEvent.METADATA_CONSUMED) : null;
		return v instanceof Boolean b && b;
	}

	private boolean shouldPersist(ChatMessage message, String sessionId) {
		if (!this.messageFilter.shouldPersist(message)) {
			logger.debug("Skipping [{}] message for session [{}] — rejected by the configured MessageFilter",
					message.getRole(), sessionId);
			return false;
		}
		return true;
	}

	public static Builder builder(ISessionManager sessionService) {
		return new Builder(sessionService);
	}

	public static final class Builder {

		private final ISessionManager sessionService;

		private String defaultUserId = "default-user";

		private EventFilter eventFilter = EventFilter.all();

		private MessageFilter messageFilter = MessageFilter.skipEmptyMessages();

		private SessionEventRequestIdGenerator requestEventIdGenerator = SessionEventRequestIdGenerator.random();

		private SessionEventResponseIdGenerator responseEventIdGenerator = SessionEventResponseIdGenerator.random();

		@Nullable private CompactionTrigger compactionTrigger;

		@Nullable private CompactionStrategy compactionStrategy;

		private Builder(ISessionManager sessionService) {
			Assert.notNull(sessionService, "sessionService must not be null");
			this.sessionService = sessionService;
		}

		public Builder defaultUserId(String defaultUserId) {
			this.defaultUserId = defaultUserId;
			return this;
		}

		/**
		 * 加载会话事件历史注入提示词时应用的过滤器。默认为 {@link EventFilter#all()}。
		 * <p>
		 * branch 过滤无需手动配置：拦截器根据 ctx.branch() 自动合并
		 * {@link EventFilter#forBranch(String)}。
		 */
		public Builder eventFilter(EventFilter eventFilter) {
			Assert.notNull(eventFilter, "eventFilter must not be null");
			this.eventFilter = eventFilter;
			return this;
		}

		/**
		 * 持久化前应用的消息过滤器 — 包括 beforeModelCall 持久化的当前用户/工具响应
		 * 消息和 afterModelCall 持久化的助手消息。默认为
		 * {@link MessageFilter#skipEmptyMessages()}。
		 */
		public Builder messageFilter(MessageFilter messageFilter) {
			Assert.notNull(messageFilter, "messageFilter must not be null");
			this.messageFilter = messageFilter;
			return this;
		}

		public Builder compactionTrigger(CompactionTrigger trigger) {
			this.compactionTrigger = trigger;
			return this;
		}

		public Builder compactionStrategy(CompactionStrategy strategy) {
			this.compactionStrategy = strategy;
			return this;
		}

		/**
		 * 覆盖 beforeModelCall 中持久化的会话事件（当前用户/工具响应消息）的 ID 派生方式。
		 * 默认为 {@link SessionEventRequestIdGenerator#random()}。
		 */
		public Builder requestEventIdGenerator(SessionEventRequestIdGenerator requestEventIdGenerator) {
			Assert.notNull(requestEventIdGenerator, "requestEventIdGenerator must not be null");
			this.requestEventIdGenerator = requestEventIdGenerator;
			return this;
		}

		/**
		 * 覆盖 afterModelCall / 续轮中持久化的每个会话事件（助手回复、工具结果）的
		 * ID 派生方式。默认为 {@link SessionEventResponseIdGenerator#random()}。
		 */
		public Builder responseEventIdGenerator(SessionEventResponseIdGenerator responseEventIdGenerator) {
			Assert.notNull(responseEventIdGenerator, "responseEventIdGenerator must not be null");
			this.responseEventIdGenerator = responseEventIdGenerator;
			return this;
		}

		public SessionMemoryInterceptor build() {
			if ((this.compactionTrigger == null) != (this.compactionStrategy == null)) {
				throw new IllegalArgumentException(
						"compactionTrigger and compactionStrategy must be set together — set both or neither");
			}
			return new SessionMemoryInterceptor(this.sessionService, this.defaultUserId, this.eventFilter,
					this.messageFilter, this.requestEventIdGenerator, this.responseEventIdGenerator,
					this.compactionTrigger, this.compactionStrategy);
		}

	}

}
