package cn.bitloom.controller;

import cn.bitloom.bridge.desktop.ToolUIBridge;
import cn.bitloom.constant.AgentMode;
import cn.bitloom.node.SlashCommandPopup;
import cn.bitloom.node.message.InputTag;
import cn.bitloom.holder.ButtonBarHolder;
import cn.bitloom.project.ProjectInfo;
import cn.bitloom.store.Store;
import cn.bitloom.vm.AbstractHomePageViewModel;
import cn.bitloom.vm.CodeHomePageViewModel;
import cn.bitloom.window.WindowManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * Coder 模式首页控制器。
 * <p>
 * 在通用对话逻辑基础上增加：
 * - 项目选择菜单 + 分支显示按钮
 * - 终端按钮配置
 */
@Slf4j
@Component
public class CodeHomePageController extends AbstractHomePageController {

    @FXML
    private VBox approvalBar;
    @FXML
    private MenuButton projectSelectButton;
    @FXML
    private Button branchDisplayButton;

    private final CodeHomePageViewModel viewModel;

    /** 斜杠命令：设置目标。用法：/goal 描述 */
    private static final String CMD_GOAL = "/goal";
    /** 斜杠命令：清除当前目标。用法：/clear-goal */
    private static final String CMD_CLEAR_GOAL = "/clear-goal";
    /** 斜杠命令：切换计划模式。用法：/plan */
    private static final String CMD_PLAN = "/plan";

    private SlashCommandPopup slashPopup;

    public CodeHomePageController(ToolUIBridge toolUIBridge,
                                  WindowManager windowManager,
                                  CodeHomePageViewModel viewModel) {
        super(toolUIBridge, windowManager);
        this.viewModel = viewModel;
    }

    /**
     * 统一计算输入框提示文案：计划模式 > 默认。Goal/Plan 改为斜杠命令后无独立待输入状态。
     */
    private void updateSendFieldPrompt() {
        if (Boolean.TRUE.equals(this.viewModel.planModeProperty().get())) {
            sendField.setPromptText("描述你的任务，呆芽将只读调研并制定计划...");
        } else {
            sendField.setPromptText("给呆芽发消息...");
        }
        sendField.requestLayout();
    }

    /**
     * 斜杠命令拦截钩子（基类 handleSendMessage 中，tag 替换后调用）：
     * 识别 /goal、/clear-goal、/plan 并就地执行，不转发给 agent。
     */
    @Override
    protected boolean interceptSlashCommand(String message) {
        return handleSlashCommand(message);
    }

    /**
     * 解析并执行斜杠命令。命中命令返回 true，否则返回 false。
     */
    private boolean handleSlashCommand(String text) {
        if (text.equalsIgnoreCase(CMD_PLAN)) {
            this.viewModel.togglePlanMode();
            return true;
        }
        if (text.equalsIgnoreCase(CMD_CLEAR_GOAL)) {
            this.viewModel.clearGoal();
            return true;
        }
        if (text.toLowerCase().startsWith(CMD_GOAL + " ") || text.equalsIgnoreCase(CMD_GOAL)) {
            String description = text.substring(CMD_GOAL.length()).trim();
            if (description.isBlank()) {
                Store.warnMessage.set("请为目标描述附加内容，例如：/goal 完成登录功能并验证");
                return true;
            }
            this.viewModel.setGoal(description);
            return true;
        }
        return false;
    }

    /**
     * 初始化斜杠命令自动补全弹窗：输入以 "/" 开头（且不含空格）时，
     * 在输入框上方弹出候选命令列表，↑/↓ 选择、回车回填命令到输入框。
     */
    private void setupSlashCommandPopup() {
        slashPopup = new SlashCommandPopup(cmd -> {
            // 先删除触发弹窗时已输入的斜杠前缀（如只输入了 "/"），避免残留多余斜杠
            int prefixLen = Math.min(sendField.getCaretPosition(), sendField.getLength());
            if (prefixLen > 0) {
                sendField.deleteText(0, prefixLen);
            }
            // 以命令 tag 形式插入输入框（⟦⚡/plan⟧），发送时经 tag 替换还原为命令原文
            insertTag(InputTag.forCommand(cmd.trim()));
        });
        slashPopup.setCommands(List.of(
                new SlashCommandPopup.CommandOption(CMD_GOAL + " ", "设置目标（结束状态+验证方式+限制条件）"),
                new SlashCommandPopup.CommandOption(CMD_CLEAR_GOAL, "清除当前目标"),
                new SlashCommandPopup.CommandOption(CMD_PLAN, "切换计划模式")
        ));

        // 文本变化：以 "/" 开头（且不含空格）时弹出候选以匹配前缀，否则隐藏。
        // 命令 tag 插入后文本以 ⟦ 开头，不会误触发。
        sendField.textProperty().addListener((obs, oldVal, newVal) -> {
            String text = (newVal == null) ? "" : newVal.trim();
            if (text.startsWith("/") && !text.contains(" ")) {
                slashPopup.show(sendField, text);
            } else {
                slashPopup.hide();
            }
        });

        // 键盘拦截：弹窗可见时 ↑/↓ 选择、回车回填（阻止发送）、Esc 隐藏
        sendField.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (!slashPopup.isShowing()) {
                return;
            }
            switch (e.getCode()) {
                case UP -> {
                    slashPopup.moveUp();
                    e.consume();
                }
                case DOWN -> {
                    slashPopup.moveDown();
                    e.consume();
                }
                case ENTER -> {
                    slashPopup.confirm();
                    e.consume();
                }
                case ESCAPE -> {
                    slashPopup.hide();
                    e.consume();
                }
                default -> {
                }
            }
        });
    }

    /**
     * Coder 模式未选择项目时锁定发送输入框，必须选择项目才能输入。
     */
    @Override
    protected boolean isSendInputLocked() {
        return this.viewModel.getCurrentProject() == null;
    }

    @Override
    public AbstractHomePageViewModel getViewModel() {
        return viewModel;
    }

    @Override
    public void initialize(java.net.URL location, java.util.ResourceBundle resources) {
        super.initialize(location, resources);

        // 智能体切换联动：控制项目/分支按钮可见性
        updateProjectButtonBarVisibility(Store.currentAgent.get());
        Store.currentAgent.addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> updateProjectButtonBarVisibility(newVal));
        });

        this.setupProjectMenu();
        this.branchDisplayButton.setText("");
        this.viewModel.currentProjectProperty()
                .addListener((obs, oldVal, newVal) -> {
                    refreshBranchDisplay(newVal);
                    refreshProjectMenuText(newVal);
                });

        // 批准框：显示在输入框上方的 approvalBar（不持久化到聊天历史）
        this.toolUIBridge.setOnShowApproval(card -> {
            approvalBar.getChildren().clear();
            approvalBar.getChildren().add(card);
            approvalBar.setVisible(true);
            approvalBar.setManaged(true);
        });

        // 计划批准卡片（Plan Mode）：同样显示在 approvalBar
        this.toolUIBridge.setOnShowPlanApproval(card -> {
            // 点击计划路径在应用内文件视图打开计划文档（而非系统文件管理器）
            if (card instanceof cn.bitloom.node.tool.PlanApprovalCard planCard) {
                planCard.setFileOpener(file -> {
                    if (indexController != null) {
                        indexController.showFileInPanel(file);
                    }
                });
            }
            approvalBar.getChildren().clear();
            approvalBar.getChildren().add(card);
            approvalBar.setVisible(true);
            approvalBar.setManaged(true);
        });

        // approvalBar 内容为空时自动隐藏（卡片决策后 dismiss 移除自身）
        this.approvalBar.getChildren().addListener((javafx.collections.ListChangeListener<Node>) change -> {
            if (this.approvalBar.getChildren().isEmpty()) {
                this.approvalBar.setVisible(false);
                this.approvalBar.setManaged(false);
            }
        });

        // Goal/Plan 改为斜杠命令（/goal、/clear-goal、/plan）后在输入框触发，无需按钮。
        // 监听模式状态：计划模式/goal 激活切换时刷新输入提示，项目切换时刷新输入锁定态
        this.viewModel.planModeProperty().addListener((obs, oldVal, newVal) ->
                updateSendFieldPrompt());
        this.viewModel.goalActiveProperty().addListener((obs, oldVal, newVal) ->
                updateSendFieldPrompt());
        this.viewModel.currentProjectProperty().addListener((obs, oldVal, newVal) ->
                refreshSendInputDisabled());
        refreshSendInputDisabled();

        setupSlashCommandPopup();

        // 监听会话切换：清空批准框
        Store.currentSessionId.addListener((obs, oldVal, newVal) -> {
            if (oldVal != null && !oldVal.equals(newVal)) {
                approvalBar.getChildren().clear();
                approvalBar.setVisible(false);
                approvalBar.setManaged(false);
            }
        });
    }

    @Override
    protected void onMessagesChanged(boolean hasMessages) {
        updateSelectorLockState(hasMessages);
    }

    // ===== 项目菜单与分支显示 =====

    private void updateProjectButtonBarVisibility(String agentId) {
        boolean show = AgentMode.CODE.matches(agentId);
        projectSelectButton.setVisible(show);
        projectSelectButton.setManaged(show);
        branchDisplayButton.setVisible(show);
        branchDisplayButton.setManaged(show);
    }

    private void updateSelectorLockState(boolean locked) {
        projectSelectButton.setDisable(locked);
    }

    private void setupProjectMenu() {
        refreshProjectMenu();
        refreshProjectMenuText(viewModel.getCurrentProject());
    }

    private void refreshProjectMenu() {
        projectSelectButton.getItems().clear();

        MenuItem openFolderItem = new MenuItem("选择文件夹...");
        openFolderItem.setOnAction(e -> handleOpenLocalFolder());
        projectSelectButton.getItems().add(openFolderItem);

        projectSelectButton.getItems().add(new SeparatorMenuItem());

        List<ProjectInfo> projects = viewModel.listProjects();
        for (ProjectInfo project : projects) {
            MenuItem item = new MenuItem(project.name());
            item.setOnAction(e -> {
                viewModel.setCurrentProject(project);
                refreshBranchDisplay(project);
            });
            projectSelectButton.getItems().add(item);
        }
    }

    private void refreshProjectMenuText(ProjectInfo project) {
        if (project != null) {
            projectSelectButton.setText(project.name());
        } else {
            projectSelectButton.setText("选择项目");
        }
    }

    private void handleOpenLocalFolder() {
        try {
            javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
            dirChooser.setTitle("选择项目文件夹");
            javafx.stage.Stage stage = (javafx.stage.Stage) projectSelectButton.getScene().getWindow();
            File selectedDir = dirChooser.showDialog(stage);
            if (selectedDir != null) {
                String path = selectedDir.getAbsolutePath();
                String name = selectedDir.getName();
                viewModel.registerLocalProject(path, name);
                refreshProjectMenu();
            }
        } catch (Exception e) {
            log.error("打开文件夹选择器失败", e);
        }
    }

    private void refreshBranchDisplay(ProjectInfo project) {
        if (project == null || project.gitBranch() == null || project.gitBranch().isBlank()) {
            branchDisplayButton.setText("");
        } else {
            branchDisplayButton.setText(project.gitBranch());
        }
    }

    // ===== 按钮配置 =====

    @Override
    public List<ButtonBarHolder.ButtonConfig> getButtonConfigs() {
        return createCommonButtons();
    }

    @Override
    protected void onResetForNewSession() {
    }

    /**
     * 取消事件订阅（模式切换时调用）
     */
    @Override
    public void dispose() {
        super.dispose();
    }
}
