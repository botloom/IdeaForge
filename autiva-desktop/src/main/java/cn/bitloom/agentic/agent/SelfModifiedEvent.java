package cn.bitloom.agentic.agent;

/**
 * 自修改事件 — 智能体修改了自身的构成（技能 / 智能体定义 / 动态插件）后发布。
 * <p>
 * 监听方（如首页 ViewModel）收到后 evict 对应 session 的 Agent 缓存，
 * 下一轮消息发送时按最新构成重建 Agent，实现热生效。
 *
 * @param sessionId 发生自修改的会话 ID
 * @param detail    变更描述（如 "reloaded skill: xxx"）
 */
public record SelfModifiedEvent(String sessionId, String detail) {
}
