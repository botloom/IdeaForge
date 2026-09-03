/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cn.bitloom.agentic.session;

import java.util.UUID;

import cn.bitloom.agentic.event.MessageEvent;
import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.loop.LoopContext;

/**
 * Derives the {@link MessageEvent#getId()} used when
 * {@code SessionMemoryInterceptor} persists the current user (or tool-response) message.
 *
 * <p>
 * The default, {@link #random()}, reproduces the id-less behaviour
 * {@code ISessionManager.appendEvent} always had before this SPI existed -- a fresh
 * random id every call, so every append is a new event. Supplying a generator that
 * derives a <em>deterministic</em> id for the same logical turn (e.g. content-addressable,
 * or reusing an upstream durability layer's own idempotency key) makes a retried append
 * an idempotent no-op instead of a duplicate, via
 * {@code SessionRepository.appendEvent}'s id-based replay contract.
 *
 * @see SessionEventResponseIdGenerator the counterpart used for assistant replies
 */
@FunctionalInterface
public interface SessionEventRequestIdGenerator {

	String generate(LoopContext ctx, ChatMessage message);

	static SessionEventRequestIdGenerator random() {
		return (ctx, message) -> UUID.randomUUID().toString();
	}

}
