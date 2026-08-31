package cn.bitloom.agentic.event;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * 记忆召回事件：会话首轮由 {@code AgentMemoryRecallAdvisor} 召回相关长期记忆后发布。
 * <p>
 * UI 渲染为「参考内容」折叠卡片：折叠态仅显示标题，展开后列出召回的记忆文件名。
 * 事件持久化到 events.jsonl，历史加载时与实时流等价重建。
 */
@Getter
@Setter
@SuperBuilder
@Jacksonized
@NoArgsConstructor
public final class MemoryRecallEvent extends AbstractEvent {

    @Builder.Default
    private EventTypeEnum eventType = EventTypeEnum.MEMORY;

    /** 召回的记忆文件名列表（相对记忆根目录，如 user_role.md） */
    private List<String> files;

    @Override
    public EventTypeEnum getEventType() { return eventType; }

    public static MemoryRecallEvent of(String sessionId, List<String> files) {
        return MemoryRecallEvent.builder()
                .sessionId(sessionId)
                .files(files)
                .build();
    }
}
