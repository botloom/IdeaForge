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
 * {@code SessionMemoryInterceptor} persists an assistant reply message.
 *
 * <p>
 * Separate from {@link SessionEventRequestIdGenerator} rather than one shared signature
 * so request-side (user/tool) and response-side (assistant) derivations can vary
 * independently. Session id and any other context needed for a deterministic
 * derivation are available via {@link LoopContext}.
 *
 * <p>
 * The default, {@link #random()}, reproduces the id-less behaviour
 * {@code ISessionManager.appendEvent} always had before this SPI existed.
 *
 * @see SessionEventRequestIdGenerator the request-side counterpart
 */
@FunctionalInterface
public interface SessionEventResponseIdGenerator {

	String generate(LoopContext ctx, ChatMessage message);

	static SessionEventResponseIdGenerator random() {
		return (ctx, message) -> UUID.randomUUID().toString();
	}

}
