package cn.bitloom.vm;

import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.session.FileSystemSessionManager;
import cn.bitloom.agentic.session.SessionTypeEnum;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.agentic.tool.command.ShellSession;
import cn.bitloom.constant.AppConstants;
import cn.bitloom.store.Store;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Work 模式首页 ViewModel。
 * <p>
 * 当前为空壳实现，预留扩展点。
 * buildMessageWithContext 直接返回原文，onSwitchAgent 空实现。
 */
@Slf4j
@Component
public class WorkHomePageViewModel extends AbstractHomePageViewModel {

    public WorkHomePageViewModel(FileSystemSessionManager fileSystemSessionManager,
                                 AgentDefinitionManager definitionManager,
                                 ModelFactory modelFactory,
                                 Toolkit toolkit,
                                 cn.bitloom.agentic.skill.SkillManager skillManager,
                                 List<cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy> approvalStrategies,
                                 cn.bitloom.config.ConfigManager configManager,
                                 cn.bitloom.agentic.tool.mcp.McpConnectionManager mcpConnectionManager,
                                 cn.bitloom.agentic.goal.GoalManager goalManager,
                                 cn.bitloom.bridge.desktop.ToolUIBridge toolUIBridge) {
        super(fileSystemSessionManager, definitionManager, modelFactory, toolkit, skillManager, approvalStrategies,
                configManager, mcpConnectionManager, goalManager, toolUIBridge);
    }

    @Override
    protected String buildMessageWithContext(String text) {
        return text;
    }

    @Override
    protected Path resolveMemoryDir() {
        // work 模式使用全局工作记忆目录
        return AppConstants.Memory.workMemoryDir();
    }

    @Override
    protected String buildSessionId() {
        return "work-" + SessionTypeEnum.DM + "-" + "desktopApp" + "-" + Store.userId.get() + "-" + System.currentTimeMillis();
    }

    @Override
    protected String buildSystemPrompt(AgentDefinition definition) {
        // work 模式无项目：仅附环境块（Working directory = 持久化 cwd）
        return definition.content() + ShellSession.envBlock();
    }

    @Override
    protected void onSwitchAgent(String agentId) {
        // work 模式无模式切换专有逻辑
    }
}
