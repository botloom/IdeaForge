package cn.bitloom.vm;

import cn.bitloom.agentic.agent.AgentDefinition;
import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.goal.GoalState;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.session.CreateSessionRequest;
import cn.bitloom.agentic.session.FileSystemSessionManager;
import cn.bitloom.agentic.session.Session;
import cn.bitloom.agentic.session.SessionTypeEnum;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.agentic.tool.command.ShellSession;
import cn.bitloom.agentic.tool.plan.ExitPlanModeTool;
import cn.bitloom.constant.AgentMode;
import cn.bitloom.constant.AppConstants;
import cn.bitloom.node.tool.PlanApprovalCard;
import cn.bitloom.project.ProjectInfo;
import cn.bitloom.project.ProjectRegistry;
import cn.bitloom.store.Store;
import cn.bitloom.util.JsonUtils;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Coder 模式首页 ViewModel。
 * <p>
 * 在通用会话管理基础上增加项目上下文管理：
 * - currentProject 属性（供 Controller 监听）
 * - 项目列表/新建/注册本地文件夹
 * - buildMessageWithContext 直接返回原文（项目规则由 system prompt 注入）
 * - onSwitchAgent 非 coder 时清空 currentProject
 * - onSessionSwitched 从 session.metadata 恢复 currentProject
 * - slash 命令系统：/goal（目标闭环）、/plan（计划模式，批准后自动执行）
 */
@Slf4j
public class CodeHomePageViewModel extends AbstractHomePageViewModel {

    private final ProjectRegistry projectRegistry;
    private final cn.bitloom.project.GitService gitService;
    private final ObjectProperty<ProjectInfo> currentProject = new SimpleObjectProperty<>();

    /** 已批准待执行的计划（批准时记录，当前计划模式流结束后自动发起执行轮） */
    private volatile PendingPlan pendingPlan;

    /**
     * Plan Mode（计划模式）：code 模式经 /plan 命令开关。
     * 开启后构建的智能体仅保留只读探索工具，经 ExitPlanMode 提交计划等待批准。
     */
    private final BooleanProperty planMode = new SimpleBooleanProperty(false);

    /**
     * Goal Loop 活跃状态：目标设置后为 true，达成 / 无法达成 / 暂停 / 清除后为 false。
     */
    private final BooleanProperty goalActive = new SimpleBooleanProperty(false);

    private record PendingPlan(String sessionId, String plan) {
    }

    public CodeHomePageViewModel(FileSystemSessionManager fileSystemSessionManager,
                                 AgentDefinitionManager definitionManager,
                                 ModelFactory modelFactory,
                                 Toolkit toolkit,
                                 cn.bitloom.agentic.skill.SkillManager skillManager,
                                 List<cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy> approvalStrategies,
                                 cn.bitloom.config.ConfigManager configManager,
                                 cn.bitloom.agentic.tool.mcp.McpConnectionManager mcpConnectionManager,
                                 cn.bitloom.agentic.goal.GoalManager goalManager,
                                 cn.bitloom.bridge.desktop.ToolUIBridge toolUIBridge,
                                 cn.bitloom.agentic.plugin.PluginRegistry pluginRegistry,
                                 ProjectRegistry projectRegistry,
                                 cn.bitloom.project.GitService gitService) {
        super(fileSystemSessionManager, definitionManager, modelFactory, toolkit, skillManager, approvalStrategies,
                configManager, mcpConnectionManager, goalManager, toolUIBridge, pluginRegistry);
        this.projectRegistry = projectRegistry;
        this.gitService = gitService;
        // Goal Loop 自动续轮：GoalJudgeHook / 后台任务通知通过 GoalManager 触发，
        // 本 VM 仅处理自己管理过的 session（sessionStates 路由），非本 VM 的静默忽略。
        goalManager.registerContinuation(this::continueRound);
    }

    /**
     * 当前项目属性（供 Controller 监听）
     */
    public ObjectProperty<ProjectInfo> currentProjectProperty() {
        return currentProject;
    }

    public void setCurrentProject(ProjectInfo project) {
        currentProject.set(project);
    }

    /**
     * code 模式当前项目（供 Controller / 内部便捷访问）。
     * 基类不定义此方法，work 模式无项目概念。
     */
    public ProjectInfo getCurrentProject() {
        return currentProject.get();
    }

    public BooleanProperty planModeProperty() {
        return planMode;
    }

    public boolean isPlanMode() {
        return planMode.get();
    }

    public BooleanProperty goalActiveProperty() {
        return goalActive;
    }

    /**
     * code 模式按 session.metadata 的 projectId 解析 projectPath：
     * Goal 自动续轮时 goal session 可能已非 active，不能依赖当前 UI 的 currentProject。
     */
    @Override
    protected String resolveProjectPath(Session session) {
        Object projectId = session.metadata() != null ? session.metadata().get("projectId") : null;
        if (projectId != null) {
            return projectRegistry.findById(projectId.toString())
                    .map(ProjectInfo::path)
                    .orElse(null);
        }
        return null;
    }

    @Override
    protected Path resolveMemoryDir() {
        // code 模式：memory 落在项目目录下，项目必须已选择
        ProjectInfo project = getCurrentProject();
        if (project == null) {
            throw new IllegalStateException("code 模式必须先选择项目");
        }
        return AppConstants.Memory.projectMemoryDir(project.name());
    }

    @Override
    protected String buildSessionId() {
        ProjectInfo project = getCurrentProject();
        if (project == null) {
            throw new IllegalStateException("code 模式必须先选择项目");
        }
        return "code-" + project.name() + "-" + SessionTypeEnum.DM + "-" + "desktopApp" + "-" + Store.userId.get() + "-" + System.currentTimeMillis();
    }

    @Override
    protected String buildSystemPrompt(AgentDefinition definition) {
        String systemPrompt = definition.content();
        ProjectInfo project = getCurrentProject();
        if (project != null) {
            try {
                // 注入项目规则（AUTIVA.md）
                Path projectRules = Path.of(project.path()).resolve("AUTIVA.md");
                if (Files.exists(projectRules)) {
                    systemPrompt += "\n\n# 项目规则\n" + Files.readString(projectRules);
                }
            } catch (Exception e) {
                log.warn("读取项目规则失败: {}", project.path(), e);
            }
        }
        // 计划模式：注入只读约束与计划提交要求
        if (isPlanMode()) {
            systemPrompt += "\n\n# 计划模式\n"
                    + "你正处于计划模式：只允许只读探索（读文件、搜索、查网页），"
                    + "严禁创建、修改、删除文件或执行任何有副作用的命令。\n"
                    + "任务：针对用户需求充分调研代码库后，制定具体到文件级的实施计划，"
                    + "然后调用 ExitPlanMode 工具提交计划等待用户批准。\n"
                    + "计划必须包含：将创建/修改的文件与各自改动要点、实施步骤顺序、风险与回滚方式。\n"
                    + "用户给出反馈时，按反馈调整计划并重新提交；不要在计划模式下开始实施。";
        }
        // code 模式注入项目路径作为 Working directory，让 LLM 感知项目根目录
        return systemPrompt + ShellSession.envBlock(project != null ? project.path() : null);
    }

    @Override
    protected cn.bitloom.agentic.agent.assembly.AgentProfile createProfile(
            cn.bitloom.agentic.agent.assembly.AgentAssemblyContext ctx) {
        return new cn.bitloom.agentic.agent.assembly.CodeProfile(ctx, goalManager,
                this::isPlanMode, this::onPlanSubmitted, this::onGoalUpdated);
    }

    @Override
    protected void applySessionMetadata(CreateSessionRequest.Builder builder) {
        ProjectInfo project = getCurrentProject();
        if (project != null) {
            builder.metadata("projectId", project.id());
            builder.metadata("projectName", project.name());
        }
    }

    /**
     * Goal 状态更新回调（GoalJudgeHook listener）：更新 GoalCard + 终态通知。
     * /goal 命令设置目标后复用此方法刷新卡片。
     */
    protected void onGoalUpdated(String sessionId, GoalState state) {
        // 同步 goal 按钮开关态：active 进行中，其余终态关闭
        boolean active = GoalState.STATUS_ACTIVE.equals(state.getStatus());
        goalActive.set(active);
        String goalJson = JsonUtils.toJson(Map.of(
                "goal", state.getGoal(),
                "status", state.getStatus(),
                "judgeCount", state.getJudgeCount(),
                "blockedCount", state.getBlockedCount(),
                "lastReason", state.getLastReason() != null ? state.getLastReason() : ""));
        if (toolUIBridge != null) {
            toolUIBridge.showGoal(goalJson, sessionId);
            if (GoalState.STATUS_ACHIEVED.equals(state.getStatus())) {
                toolUIBridge.showNotification("目标已达成（判定 " + state.getJudgeCount() + " 次）", sessionId);
            } else if (GoalState.STATUS_IMPOSSIBLE.equals(state.getStatus())) {
                toolUIBridge.showNotification("目标无法达成：" + state.getLastReason(), sessionId);
            } else if (GoalState.STATUS_BLOCKED.equals(state.getStatus())) {
                toolUIBridge.showNotification("目标续轮已暂停（连续 " + state.getBlockedCount()
                        + " 次未通过判定）：" + state.getLastReason(), sessionId);
            }
        }
    }

    /**
     * code 模式前置拦截：未选项目时不发话，提示用户。
     */
    @Override
    public void sendMessage(String text) {
        if (currentProject.get() == null) {
            Platform.runLater(() -> Store.warnMessage.set("请先选择项目再开始对话"));
            return;
        }
        super.sendMessage(text);
    }

    // ===== Goal / Plan 模式控制（输入区按钮触发） =====

    /**
     * 设置目标闭环（goal 按钮开启时调用，不经 LLM）。
     * 目标需包含结束状态 + 验证方式 + 限制条件。
     */
    public void setGoal(String description) {
        if (currentProject.get() == null) {
            Store.warnMessage.set("请先选择项目再设置目标");
            return;
        }
        if (description == null || description.isBlank()) {
            return;
        }
        Session s = ensureSession();
        cn.bitloom.agentic.goal.GoalState state = goalManager.setGoal(s.id(), description.trim());
        onGoalUpdated(s.id(), state);
    }

    /**
     * 清除当前目标（goal 按钮关闭时调用）。
     */
    public void clearGoal() {
        if (session == null) {
            return;
        }
        goalManager.clearGoal(session.id());
        goalActiveProperty().set(false);
        toolUIBridge.resetGoalCard();
        toolUIBridge.showNotification("目标已清除", session.id());
    }

    /**
     * 切换计划模式（plan 按钮触发）。切换后 evict 当前 session 的 Agent，
     * 下一次消息按新模式重建（计划模式：只读工具 + ExitPlanMode）。
     */
    public void togglePlanMode() {
        if (currentProject.get() == null) {
            Store.warnMessage.set("请先选择项目再使用计划模式");
            return;
        }
        boolean enter = !isPlanMode();
        planModeProperty().set(enter);
        // 互斥：进入计划模式时清除已激活的目标（goal 与 plan 二选一）
        if (enter && goalActiveProperty().get()) {
            clearGoal();
        }
        if (session != null) {
            evictAgent(session.id());
        }
        log.info("[Plan] 计划模式已{}: session={}", enter ? "开启" : "关闭",
                session != null ? session.id() : "(未创建)");
    }

    // ===== 计划批准流程（Plan Mode 闭环） =====

    /**
     * 智能体提交计划（ExitPlanModeTool 回调，工具线程）：
     * 计划保存到项目 .autiva/plan 目录，经 ToolUIBridge 显示批准条（仅路径 + 决策按钮），
     * 用户决策经 future 返回给工具。
     */
    @Override
    protected void onPlanSubmitted(String sessionId, String plan, CompletableFuture<String> future) {
        // 落盘：{projectPath}/.autiva/plan/plan-{时间戳}.md
        String filePath = savePlanFile(plan);
        Platform.runLater(() -> {
            PlanApprovalCard card = new PlanApprovalCard(
                    filePath != null ? filePath : "(保存失败，计划仅在对话中)",
                    decision -> onPlanDecided(sessionId, plan, decision, future));
            toolUIBridge.showPlanApproval(card);
        });
    }

    /** 保存计划文件，返回绝对路径（失败返回 null） */
    private String savePlanFile(String plan) {
        ProjectInfo project = getCurrentProject();
        if (project == null) {
            return null;
        }
        try {
            java.nio.file.Path planDir = java.nio.file.Path.of(project.path())
                    .resolve(".autiva").resolve("plan");
            java.nio.file.Files.createDirectories(planDir);
            String fileName = "plan-" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md";
            java.nio.file.Path file = planDir.resolve(fileName);
            java.nio.file.Files.writeString(file, plan);
            log.info("[Plan] 计划已保存: {}", file);
            return file.toString();
        } catch (Exception e) {
            log.warn("[Plan] 计划保存失败", e);
            return null;
        }
    }

    /** 用户对计划做出决策（FX 线程） */
    private void onPlanDecided(String sessionId, String plan, String decision, CompletableFuture<String> future) {
        if (ExitPlanModeTool.DECISION_APPROVED.equals(decision)) {
            // 批准：退出计划模式 + evict Agent（重建为全工具），当前流结束后自动执行
            planModeProperty().set(false);
            evictAgent(sessionId);
            this.pendingPlan = new PendingPlan(sessionId, plan);
            log.info("[Plan] 计划已批准，待当前流结束后自动执行: session={}", sessionId);
        } else if (ExitPlanModeTool.DECISION_ABANDONED.equals(decision)) {
            planModeProperty().set(false);
            evictAgent(sessionId);
            log.info("[Plan] 计划已放弃，退出计划模式: session={}", sessionId);
        }
        future.complete(decision);
    }

    /**
     * 流结束回调：批准后的计划在计划模式流收尾后自动发起执行轮
     * （新 Agent 已按非计划模式重建，具备完整工具）。
     */
    @Override
    protected void onStreamCompleted(String sessionId) {
        PendingPlan pp = this.pendingPlan;
        if (pp == null || !pp.sessionId().equals(sessionId)) {
            return;
        }
        this.pendingPlan = null;
        String message = "计划已获用户批准。请立即严格按以下计划逐项执行，无需再次确认：\n\n" + pp.plan();
        continueRound(sessionId, message);
    }

    public List<ProjectInfo> listProjects() {
        return projectRegistry.listProjects();
    }

    public ProjectInfo createNewProject(String name) throws java.io.IOException {
        ProjectInfo project = projectRegistry.createProject(name);
        currentProject.set(project);
        return project;
    }

    public void registerLocalProject(String path, String name) throws java.io.IOException {
        ProjectInfo project = projectRegistry.registerLocal(path, name);
        currentProject.set(project);
    }

    /** 列出当前项目所有本地分支（controller 分支菜单用） */
    public List<String> listBranches() {
        ProjectInfo project = getCurrentProject();
        if (project == null) {
            return List.of();
        }
        return gitService.listBranches(java.nio.file.Path.of(project.path()));
    }

    /** 当前项目工作区是否干净（不干净时禁止切换分支） */
    public boolean isWorkingTreeClean() {
        ProjectInfo project = getCurrentProject();
        if (project == null) {
            return false;
        }
        return gitService.isWorkingTreeClean(java.nio.file.Path.of(project.path()));
    }

    /**
     * 切换当前项目到指定分支。成功后刷新注册表与 currentProject 的分支信息。
     *
     * @param branch 目标分支名
     * @return 是否切换成功
     */
    public boolean switchCurrentProjectBranch(String branch) {
        ProjectInfo project = getCurrentProject();
        if (project == null) {
            return false;
        }
        java.nio.file.Path projectPath = java.nio.file.Path.of(project.path());
        if (!gitService.isWorkingTreeClean(projectPath)) {
            return false;
        }
        if (!gitService.switchBranch(projectPath, branch)) {
            return false;
        }
        // 以 git 实际当前分支为准：若切换未生效（与目标不符），回滚 UI 并视为失败
        String actual = gitService.getCurrentBranch(projectPath).orElse(null);
        if (actual == null || !actual.equals(branch)) {
            ProjectInfo stale = projectRegistry.refreshBranch(project.id());
            if (stale != null) {
                currentProject.set(stale);
            }
            return false;
        }
        ProjectInfo updated = projectRegistry.refreshBranch(project.id());
        if (updated != null) {
            currentProject.set(updated);
        }
        return true;
    }

    /**
     * 以悬浮 toast 展示一条系统通知（如分支切换结果），不写入聊天消息流。
     */
    public void showNotice(String text) {
        javafx.application.Platform.runLater(() -> {
            if (toolUIBridge != null) {
                toolUIBridge.showToast(text);
            }
        });
    }

    @Override
    protected String buildMessageWithContext(String text) {
        // 项目规则已通过 system prompt（AUTIVA.md）注入，消息中不再附加项目前缀
        return text;
    }

    @Override
    protected void onSwitchAgent(String agentId) {
        if (AgentMode.fromAgentId(agentId) != AgentMode.CODE) {
            currentProject.set(null);
        }
    }

    /**
     * 切换历史会话时从 session.metadata 恢复 currentProject。
     * 确保 coder 模式下打开历史会话后项目选择不为空。
     */
    @Override
    protected void onSessionSwitched(Session session) {
        Object projectIdObj = session.metadata().get("projectId");
        if (projectIdObj != null) {
            projectRegistry.findById(projectIdObj.toString())
                    .ifPresentOrElse(
                            this::setCurrentProject,
                            () -> {
                                log.warn("会话关联的项目已不存在: projectId={}", projectIdObj);
                                currentProject.set(null);
                            }
                    );
        } else {
            currentProject.set(null);
        }
    }
}
