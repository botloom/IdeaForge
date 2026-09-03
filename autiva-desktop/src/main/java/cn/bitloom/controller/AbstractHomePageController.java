package cn.bitloom.controller;

import cn.bitloom.agentic.model.ModelFactory;
import cn.bitloom.bridge.desktop.ToolUIBridge;
import cn.bitloom.holder.ButtonBarHolder;
import cn.bitloom.holder.PageHolder;
import cn.bitloom.node.AutoResizeTextArea;
import cn.bitloom.node.message.*;
import cn.bitloom.node.tool.TaskCard;
import cn.bitloom.node.tool.ToolCallCard;

import cn.bitloom.store.Store;
import cn.bitloom.vm.AbstractHomePageViewModel;
import cn.bitloom.window.WindowManager;
import javafx.application.Platform;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.ScrollEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.fxmisc.flowless.Cell;
import org.fxmisc.flowless.VirtualFlow;
import org.fxmisc.flowless.VirtualizedScrollPane;
import cn.bitloom.harness.llm.Role;

import java.io.File;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 首页控制器抽象基类。
 * <p>
 * 包含通用的消息列表、发送框、文件附件、拖拽、动画等逻辑。
 * 子类（CoderHomePageController / WorkHomePageController）实现模式专有逻辑。
 */
@Slf4j
public abstract class AbstractHomePageController implements Initializable, ButtonBarHolder, PageHolder {

    @FXML
    protected StackPane homePage;
    @FXML
    protected VBox homePageContent;
    @FXML
    protected VBox sendBox;
    @FXML
    protected AutoResizeTextArea sendField;
    @FXML
    protected Button sendButton;
    @FXML
    protected Button stopButton;
    @FXML
    protected Button addFileButton;
    @FXML
    protected Button canvasButton;
    @FXML
    protected MenuButton modelSelectButton;
    @FXML
    protected VBox icon;
    @FXML
    protected VBox chatListContainer;
    protected VirtualizedScrollPane<VirtualFlow<MessageCard, MessageFlowCell>> chatScrollPane;
    protected VirtualFlow<MessageCard, MessageFlowCell> chatFlow;

    /** 悬浮系统通知（toast）覆盖层宿主，叠加在 homePage 顶部，不影响原有布局 */
    @FXML
    private StackPane toastOverlay;
    /** toast 容器（置于 toastOverlay 顶部，按出现顺序垂直堆叠） */
    private VBox toastContainer;

    /**
     * 输入框中 tag 的文字标记格式：⟦📄展示文本⟧
     * 用 Unicode 数学白方括号包裹，用户正常输入不会用到。
     * 发送时用正则匹配标记，按顺序替换为 {@link #tags} 中对应的 value。
     */
    private static final String TAG_OPEN = "⟦";
    private static final String TAG_CLOSE = "⟧";
    private static final Pattern TAG_PATTERN = Pattern.compile("⟦[^⟧]*⟧");

    /** 输入框中所有 tag 的实际值，顺序与文本中标记出现顺序一致 */
    private final List<InputTag> tags = new ArrayList<>();

    /**
     * 历史消息加载提示卡片（包装为 NodeMessageCard 加入 messages 列表）
     */
    private NodeMessageCard loadingIndicatorCard = null;

    @Getter
    protected final ToolUIBridge toolUIBridge;
    @Getter
    protected final WindowManager windowManager;
    protected final ModelFactory modelFactory;

    @Getter
    @Setter
    protected IndexController indexController;

    protected AbstractHomePageController(ToolUIBridge toolUIBridge, WindowManager windowManager,
                                         ModelFactory modelFactory, cn.bitloom.config.ConfigManager configManager) {
        this.toolUIBridge = toolUIBridge;
        this.windowManager = windowManager;
        this.modelFactory = modelFactory;
        this.configManager = configManager;
    }

    /**
     * 获取当前 ViewModel（子类返回具体类型的 ViewModel）
     */
    public abstract AbstractHomePageViewModel getViewModel();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 选中配置中的模型（空则回退到第一个）
        if (Store.selectedModel.get() == null || Store.selectedModel.get().isBlank()) {
            var configs = modelFactory.listModels();
            if (!configs.isEmpty()) {
                Store.selectedModel.set(configs.get(0).id());
            }
        }
        setupModelMenu();

        this.sendButton.setOnAction(event -> this.handleSendMessage());
        this.stopButton.setOnAction(event -> this.getViewModel().pauseGeneration());

        // Enter 发送消息（Shift+Enter 换行）
        this.sendField.setPromptText("给呆芽发消息...");
        this.sendField.setOnKeyPressed(e -> {
            if (e.getCode() == javafx.scene.input.KeyCode.ENTER && !e.isShiftDown()) {
                e.consume();
                this.handleSendMessage();
            }
        });

        // 添加文件按钮
        this.addFileButton.setOnAction(event -> this.handleAddFile());

        // 画布按钮
        this.canvasButton.setOnAction(event -> this.handleOpenCanvas());

        Store.isStreaming.addListener((obs, oldVal, newVal) -> {
            boolean streaming = newVal != null && newVal;
            boolean paused = Store.isPaused.get();
            boolean showSend = !streaming || paused;
            this.sendButton.setVisible(showSend);
            this.sendButton.setManaged(showSend);
            this.stopButton.setVisible(streaming && !paused);
            this.stopButton.setManaged(streaming && !paused);
            // 输出过程中锁定模型切换
            this.refreshModelSelectDisabled();
        });
        Store.isPaused.addListener((obs, oldVal, newVal) -> {
            boolean streaming = Store.isStreaming.get();
            boolean paused = newVal != null && newVal;
            boolean showSend = !streaming || paused;
            this.sendButton.setVisible(showSend);
            this.sendButton.setManaged(showSend);
            this.stopButton.setVisible(streaming && !paused);
            this.stopButton.setManaged(streaming && !paused);
        });

        // 使用 Flowless VirtualFlow 替代 ListView，彻底解决变高 cell 重叠/闪烁问题
        this.chatFlow = VirtualFlow.createVertical(this.getViewModel().getMessages(), MessageFlowCell::new);
        this.chatScrollPane = new VirtualizedScrollPane<>(this.chatFlow);
        VBox.setVgrow(this.chatScrollPane, Priority.ALWAYS);
        this.chatListContainer.getChildren().add(this.chatScrollPane);

        // 注入 session 激活回调：切换 session 时恢复/隐藏对应 session 的编辑器面板视图，
        // 并强制滚动到底部（stickToBottom 残留旧值会导致切回的 session 不跟随最新消息）
        this.getViewModel().setSessionActivatedHandler(sessionId -> {
            onEditorPanelSessionChanged(sessionId);
            forceScrollToBottom();
        });

        // 配置 stick-to-bottom 跟随模式
        setupStickToBottom();

        // 历史消息加载期间：禁用发送按钮和输入框，显示加载提示
        this.getViewModel().historyLoadingProperty().addListener((obs, oldVal, newVal) -> {
            boolean loading = newVal != null && newVal;
            this.sendButton.setDisable(loading);
            this.refreshSendInputDisabled();
            this.addFileButton.setDisable(loading);
            if (loading) {
                if (loadingIndicatorCard == null) {
                    loadingIndicatorCard = new NodeMessageCard(createLoadingIndicator());
                    this.getViewModel().getMessages().add(loadingIndicatorCard);
                }
            } else {
                if (loadingIndicatorCard != null) {
                    this.getViewModel().getMessages().remove(loadingIndicatorCard);
                    loadingIndicatorCard = null;
                }
                // 历史加载完成：强制滚动到底部显示最新消息
                forceScrollToBottom();
            }
        });

        this.toolUIBridge.setOnNodeAdded(this::addChatNode);

        // 通用确认弹窗（撤回等场景）：使用项目统一样式的 AgentConfirmDialog（同历史消息删除弹窗）
        this.toolUIBridge.setOnConfirmDialog((message, result) -> {
            javafx.stage.Window owner = this.sendBox != null && this.sendBox.getScene() != null
                    ? this.sendBox.getScene().getWindow() : null;
            this.windowManager.showDialog("cn/bitloom/view/AgentConfirmDialog.fxml", owner, controller -> {
                if (controller instanceof AgentConfirmDialogController confirmController) {
                    confirmController.init(message, result::complete);
                }
            });
        });

        // TodoWrite 结果统一路由到右侧编辑器面板对应 session 的 Todo 视图（work/code 模式共用）
        this.toolUIBridge.setOnShowTodos((sessionId, todosJson) -> {
            if (indexController != null) {
                indexController.showTodoInPanel(sessionId, todosJson);
            }
        });

        this.getViewModel().getMessages().addListener((ListChangeListener<MessageCard>) change -> {
            while (change.next()) {
                if (change.wasAdded()) {
                    scrollToBottom();
                }
            }
            boolean hasMessages = hasRealMessages();
            onMessagesChanged(hasMessages);
            updateViewButtonVisibility(hasMessages);
        });

        if (this.getViewModel().hasHistoricalMessages()) {
            this.animateToChatState();
            this.getViewModel().prepareHistoricalMessages();
        }

        // 注册对话框为拖拽目标（接收来自文件树/Diff 列表/文件编辑器的拖拽）
        this.setupDragDrop();

        // 初始化完成后同步一次右上角视图按钮可见性（异步确保按钮已由 ButtonBar 创建）
        boolean initialHasMessages = hasRealMessages();
        Platform.runLater(() -> updateViewButtonVisibility(initialHasMessages));
    }

    /**
     * 判断 UI 消息流是否包含真实对话消息（排除系统通知卡片）。
     * 系统通知（NotificationCard）不应被当作"已开始对话"，从而避免误锁定项目/分支选择。
     */
    protected boolean hasRealMessages() {
        for (MessageCard card : this.getViewModel().getMessages()) {
            if (!(card instanceof NotificationCard)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 消息列表变化时的回调（子类可 override 实现模式专有逻辑，如锁定选择器）
     */
    protected void onMessagesChanged(boolean hasMessages) {
        // 默认空实现，子类可 override
    }

    /**
     * 统一刷新发送输入框禁用态：历史加载中或模式锁定（如 Coder 未选项目）均禁用。
     * 子类在影响 {@link #isSendInputLocked()} 的状态变化时调用。
     */
    protected void refreshSendInputDisabled() {
        boolean loading = this.getViewModel().historyLoadingProperty().get();
        this.sendField.setDisable(loading || isSendInputLocked());
    }

    /**
     * 子类可 override：返回是否因模式原因锁定输入框（如 Coder 模式未选择项目）。
     */
    protected boolean isSendInputLocked() {
        return false;
    }

    // ===== 对话区模型选择 =====

    /** 模型单选组 */
    private ToggleGroup modelToggleGroup;
    /** 选中模型持久化 */
    private final cn.bitloom.config.ConfigManager configManager;

    /**
     * 初始化模型选择下拉：每次展开时按最新配置重建菜单项（设置页增删改后无需重启）。
     * 切换模型仅影响下一轮对话（Agent 复用 per-session 缓存，发送时按选中 id 重建）。
     */
    private void setupModelMenu() {
        modelToggleGroup = new ToggleGroup();
        modelSelectButton.showingProperty().addListener((obs, wasShowing, showing) -> {
            if (showing) {
                rebuildModelMenu();
            }
        });
        rebuildModelMenu();
        // 选中模型变化时刷新按钮文字
        Store.selectedModel.addListener((obs, oldVal, newVal) -> refreshModelButtonText());
        refreshModelButtonText();
    }

    /**
     * 按当前配置重建模型菜单：RadioMenuItem 单选。
     */
    private void rebuildModelMenu() {
        modelSelectButton.getItems().clear();
        var configs = modelFactory.listModels();
        if (configs.isEmpty()) {
            MenuItem empty = new MenuItem("未配置模型，请在设置中添加");
            empty.setDisable(true);
            modelSelectButton.getItems().add(empty);
        } else {
            for (var config : configs) {
                RadioMenuItem item = new RadioMenuItem(config.name());
                item.setToggleGroup(modelToggleGroup);
                item.setSelected(config.id().equals(Store.selectedModel.get()));
                item.setOnAction(e -> {
                    Store.selectedModel.set(config.id());
                    // 持久化选中模型
                    configManager.setSelectedModelId(config.id());
                    configManager.save();
                    refreshModelButtonText();
                });
                modelSelectButton.getItems().add(item);
            }
        }
    }

    /** 输出过程中禁用模型切换按钮 */
    private void refreshModelSelectDisabled() {
        this.modelSelectButton.setDisable(Store.isStreaming.get());
    }

    /** 当前选中模型显示名；未命中配置时显示 id */
    private void refreshModelButtonText() {
        String id = Store.selectedModel.get();
        String text = id;
        if (id != null && !id.isBlank()) {
            for (var config : modelFactory.listModels()) {
                if (config.id().equals(id)) {
                    text = config.name();
                    break;
                }
            }
        }
        modelSelectButton.setText(text == null || text.isBlank() ? "选择模型" : text);
    }

    /**
     * 控制右上角视图按钮（终端/工具/待办）的显示：仅有聊天消息时显示。
     */
    private void updateViewButtonVisibility(boolean hasMessages) {
        if (indexController != null && indexController.getButtonBarController() != null) {
            indexController.getButtonBarController().setViewButtonsVisible(hasMessages);
        }
    }

    /**
     * 创建历史消息加载指示器（Apple 风格 ProgressIndicator + 文字）
     */
    private javafx.scene.layout.VBox createLoadingIndicator() {
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.setPadding(new javafx.geometry.Insets(32));
        box.setUserData("history-loading");

        javafx.scene.control.ProgressIndicator indicator = new javafx.scene.control.ProgressIndicator();
        indicator.setPrefSize(24, 24);
        indicator.setStyle("-fx-progress-color: #86868b;");

        javafx.scene.control.Label label = new javafx.scene.control.Label("加载历史对话...");
        label.setStyle("-fx-text-fill: #86868b; -fx-font-size: 13px;");

        box.getChildren().addAll(indicator, label);
        return box;
    }

    private void setupDragDrop() {
        sendBox.setOnDragOver(this::handleDragOver);
        sendBox.setOnDragDropped(this::handleDragDropped);
    }

    private void handleDragOver(DragEvent event) {
        if (event.getGestureSource() == sendBox) {
            event.consume();
            return;
        }
        Dragboard db = event.getDragboard();
        if (db.hasFiles() || db.hasString() || db.hasContent(InputTag.FILE_REF_FORMAT)) {
            event.acceptTransferModes(TransferMode.COPY);
        }
        event.consume();
    }

    private void handleDragDropped(DragEvent event) {
        boolean success = false;
        Dragboard db = event.getDragboard();
        // 优先识别文件引用（来自文件编辑器选区拖拽），再识别文件，最后识别纯文本
        if (db.hasContent(InputTag.FILE_REF_FORMAT)) {
            Object raw = db.getContent(InputTag.FILE_REF_FORMAT);
            if (raw instanceof String encoded) {
                InputTag tag = InputTag.decodeFileRef(encoded);
                if (tag != null) {
                    insertTag(tag);
                    success = true;
                }
            }
        } else if (db.hasFiles()) {
            for (File file : db.getFiles()) {
                insertTag(InputTag.forFile(file));
            }
            success = true;
        } else if (db.hasString()) {
            String text = db.getString();
            if (text != null && !text.isBlank()) {
                insertTextAtCaret(text);
                success = true;
            }
        }
        event.setDropCompleted(success);
        event.consume();
    }

    /**
     * 为消息卡片组装行视图（仅 card + row 对齐，不再包外层 card wrapper）。
     * AI 侧消息占满整个可用区域，无宽度限制；用户消息保留气泡宽度。
     */
    private HBox buildMessageRow(MessageCard card) {
        if (card.getMessageType() == Role.USER) {
            card.maxWidthProperty().bind(
                    Bindings.max(100, chatScrollPane.widthProperty().subtract(32).multiply(0.75))
            );
        } else {
            card.maxWidthProperty().unbind();
            card.setMaxWidth(Double.MAX_VALUE);
        }

        if (card instanceof AssistantMessageCard assistantCard) {
            assistantCard.setOnContentChanged(c -> onCardContentChanged());
        }
        if (card instanceof ReasoningProcessCard processCard) {
            // 容器折叠/展开及内部子块（思考段/工具组）内容变化时同步滚动到底部
            processCard.setOnContentChanged(c -> onCardContentChanged());
        }
        if (card instanceof MemoryRecallCard recallCard) {
            // 参考内容卡片展开/折叠高度变化时同步滚动与布局刷新
            recallCard.setOnContentChanged(c -> onCardContentChanged());
        }

        return createMessageRow(card, card.getMessageType());
    }

    /**
     * Flowless Cell 实现：包装消息卡片行视图。
     * 容器使用 VBox（fillWidth 默认为 true），使内部行 HBox 能撑满宽度，
     * 从而让 createMessageRow 的 CENTER_RIGHT / CENTER_LEFT 对齐生效。
     */
    public class MessageFlowCell implements Cell<MessageCard, Node> {
        private final VBox container = new VBox();

        public MessageFlowCell(MessageCard card) {
            container.getStyleClass().add("chat-list-cell");
            updateItem(card);
        }

        @Override
        public void updateItem(MessageCard card) {
            container.getStyleClass().removeAll("chat-list-cell--user", "chat-list-cell--assistant");
            container.getChildren().clear();
            if (card == null) {
                return;
            }
            // AI 行无水平缩进占满区域；用户行保留水平留白（气泡与窗口边缘间距）
            container.getStyleClass().add(
                    card.getMessageType() == Role.USER ? "chat-list-cell--user" : "chat-list-cell--assistant");
            if (card instanceof NodeMessageCard nmc) {
                Node node = nmc.getNode();
                if (node instanceof Region region) {
                    // AI 侧节点卡片（工具组等）占满整个可用区域，无宽度限制
                    region.maxWidthProperty().unbind();
                    region.setMaxWidth(Double.MAX_VALUE);
                }
                if (node instanceof ToolCallCard toolCallCard) {
                    // 工具组卡片展开/折叠/追加条目高度变化时，同步滚动到底部
                    toolCallCard.setOnContentChanged(c -> onCardContentChanged());
                }
                container.getChildren().add(node);
            } else {
                container.getChildren().add(buildMessageRow(card));
            }
        }

        @Override
        public Node getNode() {
            return container;
        }

        @Override
        public boolean isReusable() {
            return true;
        }
    }

    /**
     * session 激活时同步编辑器面板视图，确保只显示当前 active session 的产物：
     * 恢复该 session 的 Todo 视图（无则收起正在显示的其他 session 的 todo）。
     * 子类 override 追加模式专有同步（如 code 重置 goal 卡片引用）。
     */
    protected void onEditorPanelSessionChanged(String sessionId) {
        if (indexController != null && indexController.getEditorPanelController() != null) {
            indexController.getEditorPanelController().restoreTodoForSession(sessionId);
        }
    }

    /**
     * toolUIBridge 回调：将工具节点添加到聊天区或编辑器面板。
     * sessionId 为节点所属会话，由 ViewModel 路由到对应会话的消息列表（active 才实时显示）。
     * 由 HomePageRouter 在模式切换时重绑定。
     */
    public void addChatNode(String sessionId, Node node) {
        if (node instanceof Region region) {
            region.maxWidthProperty().bind(
                    Bindings.max(100, chatScrollPane.widthProperty().subtract(32).multiply(0.85))
            );
        }
        if (node instanceof TaskCard taskCard) {
            taskCard.setOnContentChanged(c -> onCardContentChanged());
        }
        this.getViewModel().addNodeMessage(sessionId, node);
    }

    private HBox createMessageRow(Node card, Role type) {
        HBox row = new HBox();
        row.getStyleClass().add("chat-row");
        row.setMaxWidth(Double.MAX_VALUE);

        if (type == Role.USER) {
            row.setAlignment(Pos.CENTER_RIGHT);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            row.getChildren().addAll(spacer, card);
        } else {
            row.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(card);
        }

        return row;
    }

    protected void handleSendMessage() {
        String message = buildMessage();
        if (message.isBlank()) {
            return;
        }
        // 斜杠命令拦截（子类实现）：命中命令就地执行，不发送给 agent
        if (interceptSlashCommand(message)) {
            this.sendField.clear();
            this.tags.clear();
            return;
        }
        if (!this.chatListContainer.isVisible()) {
            this.animateToChatState();
        }

        this.getViewModel().addUserMessage(message);
        this.getViewModel().sendMessage(message);
        this.sendField.clear();
        this.tags.clear();
    }

    /**
     * 斜杠命令拦截钩子。基类默认不拦截普通消息返回 false；
     * 子类按其支持的命令集 override，命中命令就地执行并返回 true。
     * 入参 {@code message} 为 tag 替换后的最终文本（命令 tag 已还原为命令原文）。
     */
    protected boolean interceptSlashCommand(String message) {
        return false;
    }

    private void handleAddFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("选择文件");
        File selectedFile = fileChooser.showOpenDialog(this.sendBox.getScene().getWindow());
        if (selectedFile != null) {
            appendFileToChat(selectedFile);
        }
    }

    /**
     * 将选中文本插入到输入框当前光标位置（编辑器面板右键"添加到对话框"调用）。
     */
    public void appendTextToChat(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        insertTextAtCaret(text);
    }

    /**
     * 将文件以文字标记形式插入到输入框当前光标位置（拖拽文件或 + 按钮选文件）。
     */
    public void appendFileToChat(File file) {
        if (file == null) {
            return;
        }
        insertTag(InputTag.forFile(file));
    }

    /**
     * 将文件选中片段以文字标记形式插入到输入框当前光标位置（文件编辑器右键"添加到对话框"调用）。
     */
    public void appendFileRefToChat(Path filePath, int startLine, int endLine) {
        if (filePath == null) {
            return;
        }
        insertTag(InputTag.forFileRef(filePath, startLine, endLine));
    }

    /**
     * 在光标位置插入 tag 文字标记：⟦icon展示文本⟧ + 空格。
     * 同时记录到 {@link #tags} 列表，发送时按顺序替换为 value。
     * 命令 tag 用 ⚡ 前缀，文件/文本类 tag 用 📄 前缀。
     */
    protected void insertTag(InputTag tag) {
        String glyph = InputTag.Type.COMMAND.equals(tag.type()) ? "⚡" : "\uD83D\uDCC1";
        String marker = TAG_OPEN + glyph + tag.display() + TAG_CLOSE + " ";
        int pos = sendField.getCaretPosition();
        sendField.insertText(pos, marker);
        sendField.positionCaret(pos + marker.length());
        sendField.requestFocus();
        tags.add(tag);
    }

    /**
     * 在光标位置插入纯文本。
     */
    private void insertTextAtCaret(String text) {
        int pos = sendField.getCaretPosition();
        sendField.insertText(pos, text);
        sendField.positionCaret(pos + text.length());
        sendField.requestFocus();
    }

    /**
     * 构建发送消息：把输入框中的 ⟦...⟧ 标记按顺序替换为 {@link #tags} 中对应的 value。
     * 如果标记数量与 tags 不匹配，未匹配的标记保留原样。
     */
    private String buildMessage() {
        String text = sendField.getText();
        if (tags.isEmpty()) {
            return text;
        }
        Matcher matcher = TAG_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int tagIdx = 0;
        int last = 0;
        while (matcher.find()) {
            sb.append(text, last, matcher.start());
            if (tagIdx < tags.size()) {
                sb.append(tags.get(tagIdx++).value());
            } else {
                sb.append(matcher.group());
            }
            last = matcher.end();
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private void handleOpenCanvas() {
        windowManager.showDialog("cn/bitloom/view/CanvasDialog.fxml", this.sendBox.getScene().getWindow(), controller -> {
            if (controller instanceof CanvasDialogController canvasController) {
                canvasController.setOnSendToChat(this::handleCanvasContent);
            }
        });
    }

    private void handleCanvasContent(String canvasContent) {
        if (!this.chatListContainer.isVisible()) {
            this.animateToChatState();
        }
        this.getViewModel().addUserMessage(canvasContent);
        this.getViewModel().sendMessage(canvasContent);
    }

    // ===== stick-to-bottom 跟随模式 =====
    private boolean stickToBottom = true;

    private void setupStickToBottom() {
        // 鼠标滚轮向上滚动 → 停止跟随
        chatScrollPane.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.getDeltaY() > 0) {
                stickToBottom = false;
            }
        });
        // 滚回底部 → 恢复跟随
        chatScrollPane.estimatedScrollYProperty().addListener((obs, old, y) -> {
            double total = chatScrollPane.getTotalHeightEstimate();
            double viewport = chatScrollPane.getHeight();
            if (total - y <= viewport + 10) {
                stickToBottom = true;
            }
        });
    }

    /**
     * 滚动到底部。Flowless 正确处理变高 cell，无需手动 layout()。
     */
    private void scrollToBottom() {
        if (!stickToBottom) return;
        chatScrollPane.scrollYToPixel(Double.MAX_VALUE);
    }

    /**
     * 强制滚动到底部（session 切换 / 历史加载完成时）。
     * 重置跟随标记（跨 session 残留的 false 会导致不跟随），并延迟两帧执行：
     * messages.setAll 后 VirtualFlow 需要一个布局 pass 才能得到正确的高度估算。
     */
    private void forceScrollToBottom() {
        stickToBottom = true;
        Platform.runLater(() -> {
            chatScrollPane.scrollYToPixel(Double.MAX_VALUE);
            Platform.runLater(() -> chatScrollPane.scrollYToPixel(Double.MAX_VALUE));
        });
    }

    /**
     * 卡片内容高度变化时触发。Flowless 自动处理 cell 重定位，只需滚动到底部。
     */
    private void onCardContentChanged() {
        scrollToBottom();
    }

    protected void animateToChatState() {
        Timeline timeline = new Timeline();
        KeyFrame keyFrame = new KeyFrame(Duration.millis(600),
                new KeyValue(this.icon.opacityProperty(), 0, Interpolator.EASE_BOTH),
                new KeyValue(this.icon.translateYProperty(), -60, Interpolator.EASE_BOTH));

        timeline.getKeyFrames().add(keyFrame);

        timeline.setOnFinished(event -> {
            this.icon.setVisible(false);
            this.icon.setManaged(false);

            this.homePageContent.setAlignment(Pos.BOTTOM_CENTER);

            this.chatListContainer.setVisible(true);
            this.chatListContainer.setManaged(true);
        });

        timeline.play();
    }

    /**
     * 在聊天区域上方以悬浮 toast（类似浏览器 alert）展示一条系统通知，数秒后自动淡出。不写入消息流。
     */
    public void showToast(String text) {
        Platform.runLater(() -> {
            if (toastOverlay == null) {
                return;
            }
            // 若容器为空已从 overlay 移除，重建并重新挂载
            if (toastContainer == null) {
                toastContainer = new VBox(10);
                // 子节点不填充拉伸，按内容自适应宽度（受 min/max 约束）
                toastContainer.setFillWidth(false);
                toastContainer.setAlignment(Pos.TOP_CENTER);
                StackPane.setAlignment(toastContainer, Pos.TOP_CENTER);
                toastOverlay.getChildren().add(toastContainer);
            }
            Label label = new Label(text);
            label.setMinWidth(90);
            label.setMaxWidth(420);
            label.setWrapText(true);
            label.getStyleClass().add("toast-label");
            toastContainer.getChildren().add(label);

            // 淡入 → 停留 → 淡出并移除
            label.setOpacity(0);
            FadeTransition fadeIn = new FadeTransition(Duration.millis(180), label);
            fadeIn.setToValue(1);
            PauseTransition hold = new PauseTransition(Duration.millis(2800));
            FadeTransition fadeOut = new FadeTransition(Duration.millis(320), label);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(e -> {
                toastContainer.getChildren().remove(label);
                if (toastContainer.getChildren().isEmpty() && toastOverlay != null) {
                    toastOverlay.getChildren().remove(toastContainer);
                    toastContainer = null;
                }
            });
            new SequentialTransition(fadeIn, hold, fadeOut).play();
        });
    }

    @Override
    public void show() {
        homePage.setVisible(true);
        homePage.setManaged(true);
    }

    @Override
    public void hide() {
        homePage.setVisible(false);
        homePage.setManaged(false);
    }

    /**
     * 首页通用按钮配置（Work 与 Coder 共有）。当前无右侧视图按钮。
     */
    protected List<ButtonBarHolder.ButtonConfig> createCommonButtons() {
        return new ArrayList<>();
    }

    /**
     * 重置 UI 为新会话状态。
     * <p>
     * 仅重置 UI 元素（sendField、tags、icon、chatListContainer 可见性等）和 EditorPanel 工具卡片。
     * <b>不再清空 messages 或重新加载历史</b>——messages 内容完全由 ViewModel 管理
     * （createNewSession 会 clear，switchToSession 会 setAll 恢复或加载历史）。
     * 根据 messages 是否为空决定显示初始 icon 状态还是聊天状态。
     */
    public void resetForNewSession() {
        this.sendField.clear();
        this.tags.clear();

        // 子类专有重置逻辑
        onResetForNewSession();

        // 根据 messages 内容决定 UI 状态：有内容则切换到聊天视图，无内容则显示初始 icon
        if (!this.getViewModel().getMessages().isEmpty()) {
            animateToChatState();
        } else {
            this.homePageContent.setAlignment(Pos.CENTER);
            VBox.setMargin(this.sendBox, new Insets(0, 0, 0, 0));
            this.chatListContainer.setVisible(false);
            this.chatListContainer.setManaged(false);

            this.icon.setVisible(true);
            this.icon.setManaged(true);
            this.icon.setOpacity(1);
            this.icon.setTranslateY(0);
        }
    }

    /**
     * 子类实现模式专有的重置逻辑
     */
    protected abstract void onResetForNewSession();

    /**
     * 释放资源（模式切换时调用，取消事件订阅）。
     * 子类可 override 扩展清理逻辑，但必须 super.dispose()。
     */
    public void dispose() {
        getViewModel().dispose();
    }
}
