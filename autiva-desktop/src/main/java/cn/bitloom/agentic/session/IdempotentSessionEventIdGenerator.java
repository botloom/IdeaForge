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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.harness.llm.ToolCall;
import cn.bitloom.harness.llm.ToolResult;
import cn.bitloom.harness.loop.LoopContext;

/**
 * A deterministic {@link SessionEventRequestIdGenerator}/
 * {@link SessionEventResponseIdGenerator} that makes retried
 * {@code SessionMemoryInterceptor} writes idempotent instead of a fresh random id every
 * call. Implements both generator interfaces via overloaded {@code generate} methods,
 * sharing one hybrid derivation:
 *
 * <ul>
 * <li>For a tool-call/tool-response message, reuse the model's own
 * {@code ToolCall}/{@code ToolResult} ids -- already globally unique per model turn,
 * and the natural idempotency key for any tool-calling durability layer sitting
 * elsewhere in the same application, so both stay consistent on the same identity.
 * <li>Otherwise (plain user/system/assistant-text messages), fall back to a SHA-256
 * content hash.
 * </ul>
 * Both are prefixed with a context key/value fingerprint -- see below -- so the same
 * fallback and prefixing logic applies uniformly to every message type; prefixing more
 * distinguishing context never causes a false collision, it can only add uniqueness.
 *
 * <p>
 * <strong>Context key fingerprint:</strong> ids are derived from the values of a
 * configurable list of {@link LoopContext} params (joined in the order given). The
 * no-arg constructor defaults to {@code ["sessionId"]} -- session-scoped ids.
 * {@link #IdempotentSessionEventIdGenerator(String...)} takes the *complete* key list
 * instead of appending to that default, so a caller who also wants session-scoping
 * alongside another key must list {@code "sessionId"} explicitly.
 * Folding in a run id makes ids retry-scoped rather than only session-scoped: a genuine
 * retry (same run id, same content) still dedupes, but two distinct calls that happen to
 * share identical content no longer collide just because they share a session.
 *
 * <p>
 * Known limitation of the content-hash fallback: two <em>legitimately</em> identical
 * messages appended back-to-back within the same fingerprint (e.g. the user literally
 * sends "hi" twice in the same run) collide and the second is dropped as an apparent
 * replay -- add a key that varies per logical turn (or prefer the tool-id path) if that
 * distinction matters.
 */
public final class IdempotentSessionEventIdGenerator
		implements SessionEventRequestIdGenerator, SessionEventResponseIdGenerator {

	private final List<String> contextKeys;

	/** Session-scoped ids only, equivalent to {@code ("sessionId")}. */
	public IdempotentSessionEventIdGenerator() {
		this("sessionId");
	}

	/**
	 * @param contextKeys the complete, ordered list of {@link LoopContext} param keys to
	 * fingerprint -- not appended to the no-arg default, so include {@code "sessionId"}
	 * explicitly if session-scoping is still wanted alongside the other key(s).
	 */
	public IdempotentSessionEventIdGenerator(String... contextKeys) {
		this.contextKeys = List.of(contextKeys);
	}

	@Override
	public String generate(LoopContext ctx, ChatMessage message) {
		return deriveEventId(contextFingerprint(ctx), message);
	}

	private String contextFingerprint(LoopContext ctx) {
		return this.contextKeys.stream()
			.map(key -> key + ":" + (ctx != null ? ctx.getParam(key) : null))
			.collect(Collectors.joining(","));
	}

	private static String deriveEventId(String contextFingerprint, ChatMessage message) {
		if (message.getRole() == Role.ASSISTANT && message.hasToolCalls()) {
			String callIds = message.getToolCalls()
				.stream()
				.map(ToolCall::id)
				.collect(Collectors.joining(","));
			return contextFingerprint + ":toolcall:" + callIds;
		}
		if (message.getRole() == Role.TOOL && message.getToolResults() != null) {
			String responseIds = message.getToolResults()
				.stream()
				.map(ToolResult::id)
				.collect(Collectors.joining(","));
			return contextFingerprint + ":toolresp:" + responseIds;
		}
		String text = message.getText() == null ? "" : message.getText();
		String hash = sha256Hex(contextFingerprint + ":" + message.getRole() + ":" + text);
		return contextFingerprint + ":" + message.getRole() + ":" + hash;
	}

	private static String sha256Hex(String input) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 not available", ex);
		}
	}

}
