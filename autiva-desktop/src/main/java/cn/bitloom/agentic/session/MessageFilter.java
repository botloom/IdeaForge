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

import java.util.Set;

import cn.bitloom.harness.llm.ChatMessage;
import cn.bitloom.harness.llm.Role;
import cn.bitloom.util.Assert;

/**
 * Decides which {@link ChatMessage}s get appended (persisted) to session memory.
 *
 * <p>
 * This is the write-side counterpart of {@link EventFilter}: an {@code EventFilter}
 * selects which <em>stored</em> events are loaded into the next prompt, while a
 * {@code MessageFilter} decides whether a message is stored at all. A message rejected by
 * this filter is never persisted and therefore never replayed on later requests.
 *
 * <p>
 * Filters can consider the message role as well as the message content, and compose via
 * {@link #and(MessageFilter)}, {@link #or(MessageFilter)} and {@link #negate()}:
 *
 * <pre>{@code
 * MessageFilter filter = MessageFilter.byMessageType(Role.USER, Role.ASSISTANT)
 *     .and(MessageFilter.skipEmptyMessages());
 * }</pre>
 */
@FunctionalInterface
public interface MessageFilter {

	/**
	 * Returns {@code true} if the given message should be appended to session memory.
	 * @param message the message about to be persisted
	 */
	boolean shouldPersist(ChatMessage message);

	/**
	 * Returns a composed filter that persists a message only if both this filter and
	 * {@code other} accept it.
	 */
	default MessageFilter and(MessageFilter other) {
		Assert.notNull(other, "other must not be null");
		return message -> this.shouldPersist(message) && other.shouldPersist(message);
	}

	/**
	 * Returns a composed filter that persists a message if either this filter or
	 * {@code other} accepts it.
	 */
	default MessageFilter or(MessageFilter other) {
		Assert.notNull(other, "other must not be null");
		return message -> this.shouldPersist(message) || other.shouldPersist(message);
	}

	/** Returns a filter that represents the logical negation of this filter. */
	default MessageFilter negate() {
		return message -> !this.shouldPersist(message);
	}

	/** Returns a filter that persists every message (no filtering). */
	static MessageFilter all() {
		return message -> true;
	}

	/**
	 * Skips ASSISTANT 消息中无内容者 — 空白/null 文本且无工具调用。
	 * 某些模型会输出这种空帧，回放为历史时会被 API 拒绝；其余角色无条件持久化。
	 */
	static MessageFilter skipEmptyMessages() {
		return message -> !(message.getRole() == Role.ASSISTANT
				&& (message.getText() == null || message.getText().isBlank())
				&& !message.hasToolCalls());
	}

	/**
	 * Persists only messages whose {@link ChatMessage#getRole()} is among the given
	 * roles.
	 * @param roles the message roles to persist; must not be empty
	 */
	static MessageFilter byMessageType(Role... roles) {
		Assert.notEmpty(roles, "types must not be empty");
		Set<Role> roleSet = Set.of(roles);
		return message -> message.getRole() != null && roleSet.contains(message.getRole());
	}

	/**
	 * Persists only messages whose text contains the given keyword (case-insensitive
	 * substring match). Messages with {@code null} text are rejected.
	 * @param keyword the search term; must not be blank
	 */
	static MessageFilter containsText(String keyword) {
		Assert.hasText(keyword, "keyword must not be blank");
		String lowerKeyword = keyword.toLowerCase();
		return message -> {
			String text = message.getText();
			return text != null && text.toLowerCase().contains(lowerKeyword);
		};
	}

}
