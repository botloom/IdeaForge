package cn.bitloom;

import cn.bitloom.agentic.agent.AgentDefinitionManager;
import cn.bitloom.agentic.agent.SubAgentFactory;
import cn.bitloom.agentic.cron.CronManager;
import cn.bitloom.agentic.cron.CronTaskStore;
import cn.bitloom.agentic.goal.GoalManager;
import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.agentic.permission.ApprovalService;
import cn.bitloom.agentic.permission.McpHostPolicy;
import cn.bitloom.agentic.permission.strategy.CommandApprovalStrategy;
import cn.bitloom.agentic.permission.strategy.EditApprovalStrategy;
import cn.bitloom.agentic.permission.strategy.McpToolApprovalStrategy;
import cn.bitloom.agentic.permission.strategy.PluginMountApprovalStrategy;
import cn.bitloom.agentic.permission.strategy.ToolApprovalStrategy;
import cn.bitloom.agentic.permission.strategy.WriteApprovalStrategy;
import cn.bitloom.agentic.plugin.PluginRegistry;
import cn.bitloom.agentic.session.FileSystemSessionManager;
import cn.bitloom.agentic.skill.SkillManager;
import cn.bitloom.agentic.taskboard.TaskBoardRepository;
import cn.bitloom.agentic.team.MailboxService;
import cn.bitloom.agentic.team.TeammateRegistry;
import cn.bitloom.agentic.team.TeammateRuntime;
import cn.bitloom.agentic.tool.Toolkit;
import cn.bitloom.agentic.tool.command.PersistentShellRegistry;
import cn.bitloom.agentic.tool.command.ProcessManager;
import cn.bitloom.agentic.tool.mcp.McpConnectionManager;
import cn.bitloom.agentic.tool.task.repository.DefaultTaskRepository;
import cn.bitloom.agentic.tool.task.repository.TaskRepository;
import cn.bitloom.agentic.workflow.WorkflowRegistry;
import cn.bitloom.bridge.desktop.ToolUIBridge;
import cn.bitloom.bridge.wechat.WechatILinkClient;
import cn.bitloom.bridge.wechat.WechatILinkMessageHandler;
import cn.bitloom.bridge.wechat.WechatILinkProperties;
import cn.bitloom.config.ConfigManager;
import cn.bitloom.controller.AgentConfigEditorDialogController;
import cn.bitloom.controller.AgentConfirmDialogController;
import cn.bitloom.controller.AgentInputDialogController;
import cn.bitloom.controller.AgentPageController;
import cn.bitloom.controller.ButtonBarController;
import cn.bitloom.controller.CanvasDialogController;
import cn.bitloom.controller.CodeHomePageController;
import cn.bitloom.controller.CoderEditorPanelController;
import cn.bitloom.controller.EditorPanelController;
import cn.bitloom.controller.IndexController;
import cn.bitloom.controller.ModelEditDialogController;
import cn.bitloom.controller.ProjectPickerDialogController;
import cn.bitloom.controller.SettingsPageController;
import cn.bitloom.controller.SideBarController;
import cn.bitloom.controller.SkillPageController;
import cn.bitloom.controller.TaskPageController;
import cn.bitloom.controller.WorkHomePageController;
import cn.bitloom.project.FileTreeService;
import cn.bitloom.project.GitService;
import cn.bitloom.project.ProjectRegistry;
import cn.bitloom.project.git.GitStatusService;
import cn.bitloom.project.git.ProjectFileWatcherService;
import cn.bitloom.project.git.ProjectStatusStore;
import cn.bitloom.router.HomePageRouter;
import cn.bitloom.router.RouteConfig;
import cn.bitloom.router.Router;
import cn.bitloom.util.AppScheduler;
import cn.bitloom.vm.AgentPageViewModel;
import cn.bitloom.vm.CanvasPageViewModel;
import cn.bitloom.vm.CodeHomePageViewModel;
import cn.bitloom.vm.SettingsPageViewModel;
import cn.bitloom.vm.SkillPageViewModel;
import cn.bitloom.vm.TaskPageViewModel;
import cn.bitloom.vm.WorkHomePageViewModel;
import cn.bitloom.window.WindowManager;
import javafx.util.Callback;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手工装配中心 — 替代 Spring DI 容器。
 * <p>
 * 按拓扑序 new 全部单例服务与 FXML controller，循环依赖用 {@link Supplier} 打破。
 * {@link #controllerFactory()} 供 FXMLLoader 按 controller 类型取实例。
 */
public class App {

    private final Map<Class<?>, Object> controllers = new HashMap<>();

    private final ConfigManager configManager;
    private final AppScheduler scheduler;
    private final WindowManager windowManager;
    private final ToolUIBridge toolUIBridge;
    private final WechatILinkClient wechatILinkClient;
    private final McpConnectionManager mcpConnectionManager;
    private final ProjectFileWatcherService projectFileWatcherService;
    private final CoderEditorPanelController coderEditorPanelController;

    public App() {
        // ===== 基础无依赖服务 =====
        this.configManager = new ConfigManager();
        this.scheduler = new AppScheduler(4, "app-scheduler");
        GitService gitService = new GitService();
        FileTreeService fileTreeService = new FileTreeService();
        CronTaskStore cronTaskStore = new CronTaskStore();
        TaskBoardRepository taskBoardRepository = new TaskBoardRepository();
        ProcessManager processManager = new ProcessManager();
        PersistentShellRegistry persistentShellRegistry = new PersistentShellRegistry();
        this.mcpConnectionManager = new McpConnectionManager();
        McpHostPolicy mcpHostPolicy = new McpHostPolicy();
        WorkflowRegistry workflowRegistry = new WorkflowRegistry();
        RouteConfig routeConfig = new RouteConfig();
        this.toolUIBridge = new ToolUIBridge();
        TaskRepository taskRepository = new DefaultTaskRepository();
        SkillManager skillManager = new SkillManager();
        skillManager.init();
        AgentDefinitionManager definitionManager = new AgentDefinitionManager();
        definitionManager.init();

        // ===== 依赖基础服务的 =====
        ModelFactory modelFactory = new ModelFactory(configManager);
        ApprovalService approvalService = new ApprovalService(toolUIBridge);
        GoalManager goalManager = new GoalManager(taskRepository);

        List<ToolApprovalStrategy> approvalStrategies = List.of(
                new CommandApprovalStrategy(approvalService),
                new McpToolApprovalStrategy(mcpConnectionManager, mcpHostPolicy, approvalService),
                new PluginMountApprovalStrategy(approvalService),
                new WriteApprovalStrategy(approvalService),
                new EditApprovalStrategy(approvalService));

        // ===== 循环依赖用 Supplier 打破 =====
        // FileSystemSessionManager → Supplier<PluginRegistry>（PluginRegistry → Toolkit → FileSystemSessionManager）
        // SubAgentFactory / CronManager → Supplier<Toolkit>（Toolkit → SubAgentFactory/CronManager）
        final PluginRegistry[] pluginRegistryHolder = new PluginRegistry[1];
        FileSystemSessionManager fileSystemSessionManager =
                new FileSystemSessionManager(() -> pluginRegistryHolder[0]);
        MailboxService mailboxService = new MailboxService(fileSystemSessionManager);
        TeammateRegistry teammateRegistry = new TeammateRegistry();

        final Toolkit[] toolkitHolder = new Toolkit[1];
        SubAgentFactory subAgentFactory = new SubAgentFactory(fileSystemSessionManager,
                definitionManager, modelFactory, () -> toolkitHolder[0], skillManager,
                approvalStrategies, configManager);
        TeammateRuntime teammateRuntime = new TeammateRuntime(teammateRegistry, mailboxService,
                fileSystemSessionManager, definitionManager, subAgentFactory, toolUIBridge,
                taskBoardRepository, scheduler);
        CronManager cronManager = new CronManager(scheduler, fileSystemSessionManager,
                definitionManager, modelFactory, () -> toolkitHolder[0], approvalStrategies,
                configManager, cronTaskStore);

        Toolkit toolkit = new Toolkit(skillManager, configManager, cronManager, toolUIBridge,
                mcpConnectionManager, taskRepository, processManager, persistentShellRegistry,
                fileSystemSessionManager, definitionManager, taskBoardRepository, teammateRuntime,
                teammateRegistry, mailboxService, subAgentFactory, workflowRegistry, goalManager,
                modelFactory);
        toolkitHolder[0] = toolkit;
        pluginRegistryHolder[0] = new PluginRegistry(toolkit);

        // ===== project 相关 =====
        ProjectRegistry projectRegistry = new ProjectRegistry(gitService);
        GitStatusService gitStatusService = new GitStatusService(gitService);
        ProjectStatusStore projectStatusStore = new ProjectStatusStore(gitStatusService);
        try {
            this.projectFileWatcherService = new ProjectFileWatcherService(projectStatusStore, gitStatusService);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("初始化文件监听服务失败", e);
        }

        // ===== ViewModel =====
        CodeHomePageViewModel codeHomePageViewModel = new CodeHomePageViewModel(
                fileSystemSessionManager, definitionManager, modelFactory, toolkit, skillManager,
                approvalStrategies, configManager, mcpConnectionManager, goalManager, toolUIBridge,
                pluginRegistryHolder[0], projectRegistry, gitService);
        WorkHomePageViewModel workHomePageViewModel = new WorkHomePageViewModel(
                fileSystemSessionManager, definitionManager, modelFactory, toolkit, skillManager,
                approvalStrategies, configManager, mcpConnectionManager, goalManager, toolUIBridge,
                pluginRegistryHolder[0]);
        SettingsPageViewModel settingsPageViewModel = new SettingsPageViewModel(configManager, modelFactory);
        AgentPageViewModel agentPageViewModel = new AgentPageViewModel(definitionManager);
        SkillPageViewModel skillPageViewModel = new SkillPageViewModel(skillManager);
        TaskPageViewModel taskPageViewModel = new TaskPageViewModel(cronManager);
        CanvasPageViewModel canvasPageViewModel = new CanvasPageViewModel();

        // ===== 微信（Handler ↔ Client 循环，用 Supplier 打破） =====
        WechatILinkProperties wechatILinkProperties = new WechatILinkProperties();
        final WechatILinkClient[] wechatClientHolder = new WechatILinkClient[1];
        WechatILinkMessageHandler wechatMessageHandler = new WechatILinkMessageHandler(
                fileSystemSessionManager, () -> wechatClientHolder[0], definitionManager,
                modelFactory, toolkit);
        this.wechatILinkClient = new WechatILinkClient(wechatILinkProperties, wechatMessageHandler);
        wechatClientHolder[0] = wechatILinkClient;
        wechatILinkClient.init();

        // ===== FXML controller =====
        this.windowManager = new WindowManager(this::getController);
        HomePageRouter homePageRouter = new HomePageRouter(this::getController, toolUIBridge);

        IndexController indexController = new IndexController(homePageRouter);
        Router router = new Router(indexController, routeConfig);
        indexController.setRouter(router);

        CodeHomePageController codeHomePageController = new CodeHomePageController(
                toolUIBridge, windowManager, modelFactory, configManager, codeHomePageViewModel);
        WorkHomePageController workHomePageController = new WorkHomePageController(
                toolUIBridge, windowManager, modelFactory, configManager, workHomePageViewModel);
        EditorPanelController editorPanelController = new EditorPanelController();
        this.coderEditorPanelController = new CoderEditorPanelController(projectStatusStore, gitStatusService);
        SideBarController sideBarController = new SideBarController(fileSystemSessionManager,
                projectRegistry, fileTreeService, projectStatusStore, gitStatusService,
                projectFileWatcherService, windowManager);
        SettingsPageController settingsPageController = new SettingsPageController(
                settingsPageViewModel, wechatILinkClient, windowManager);
        AgentPageController agentPageController = new AgentPageController(agentPageViewModel, windowManager);
        SkillPageController skillPageController = new SkillPageController(skillPageViewModel);
        TaskPageController taskPageController = new TaskPageController(taskPageViewModel);
        ModelEditDialogController modelEditDialogController = new ModelEditDialogController(windowManager);
        CanvasDialogController canvasDialogController = new CanvasDialogController(canvasPageViewModel);
        ProjectPickerDialogController projectPickerDialogController = new ProjectPickerDialogController(projectRegistry);
        AgentConfigEditorDialogController agentConfigEditorDialogController = new AgentConfigEditorDialogController();
        AgentInputDialogController agentInputDialogController = new AgentInputDialogController();
        AgentConfirmDialogController agentConfirmDialogController = new AgentConfirmDialogController();
        ButtonBarController buttonBarController = new ButtonBarController();

        // ===== 注册 controller =====
        register(IndexController.class, indexController);
        register(CodeHomePageController.class, codeHomePageController);
        register(WorkHomePageController.class, workHomePageController);
        register(EditorPanelController.class, editorPanelController);
        register(CoderEditorPanelController.class, coderEditorPanelController);
        register(SideBarController.class, sideBarController);
        register(SettingsPageController.class, settingsPageController);
        register(AgentPageController.class, agentPageController);
        register(SkillPageController.class, skillPageController);
        register(TaskPageController.class, taskPageController);
        register(ModelEditDialogController.class, modelEditDialogController);
        register(CanvasDialogController.class, canvasDialogController);
        register(ProjectPickerDialogController.class, projectPickerDialogController);
        register(AgentConfigEditorDialogController.class, agentConfigEditorDialogController);
        register(AgentInputDialogController.class, agentInputDialogController);
        register(AgentConfirmDialogController.class, agentConfirmDialogController);
        register(ButtonBarController.class, buttonBarController);
    }

    private void register(Class<?> type, Object instance) {
        controllers.put(type, instance);
    }

    /** FXML controllerFactory：按 controller 类型取实例。 */
    public Callback<Class<?>, Object> controllerFactory() {
        return this::getController;
    }

    private Object getController(Class<?> type) {
        Object controller = controllers.get(type);
        if (controller == null) {
            throw new IllegalStateException("未注册的 FXML controller: " + type.getName());
        }
        return controller;
    }

    public WindowManager windowManager() {
        return windowManager;
    }

    public WechatILinkClient wechatILinkClient() {
        return wechatILinkClient;
    }

    public void shutdown() {
        wechatILinkClient.destroy();
        mcpConnectionManager.closeAll();
        projectFileWatcherService.destroy();
        coderEditorPanelController.destroy();
        scheduler.close();
    }
}
