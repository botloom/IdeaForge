package cn.bitloom.agentic.agent.assembly;

import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.memory.FileSystemAgentMemoryStore;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy;
import cn.bitloom.agentic.plugin.PluginRegistry;
import cn.bitloom.agentic.session.FileSystemSessionManager;
import cn.bitloom.agentic.session.Session;
import cn.bitloom.agentic.session.compaction.TokenCountCompactionStrategy;
import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.config.ConfigManager;
import cn.bitloom.harness.llm.ChatModel;
import lombok.Builder;
import lombok.Getter;

import java.nio.file.Path;
import java.util.List;

/**
 * Agent 组装上下文 — 插件化组装期间 Profile 构建拦截器/工具所需的外部依赖集合。
 * <p>
 * 由调用方（ViewModel）每次 buildAgent 时填充；Profile 的插件在 apply 内从中取用
 * 依赖，new 出 per-Agent 的拦截器/工具实例。
 */
@Getter
@Builder
public final class AgentAssemblyContext {

    private final Session session;
    private final AgentDefinition definition;
    private final ChatModel chatModel;
    private final String uid;
    private final String modelName;

    private final FileSystemSessionManager sessionManager;
    private final SkillManager skillManager;
    private final ConfigManager configManager;
    private final List<ToolApprovalStrategy> approvalStrategies;
    private final AgentDefinitionManager definitionManager;
    private final PluginRegistry pluginRegistry;
    private final Toolkit toolkit;

    private final Path memoriesDir;
    private final FileSystemAgentMemoryStore memoryStore;

    /** 纯 token 压缩策略（会话记忆与 reactive_compact 共用同一实例）。 */
    private final TokenCountCompactionStrategy compactionStrategy;
}
