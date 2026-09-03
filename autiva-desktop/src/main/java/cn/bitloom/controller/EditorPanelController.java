package cn.bitloom.controller;

import cn.bitloom.store.Store;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;

/**
 * 编辑器面板通用基类。
 * <p>
 * 目前仅保留"文件内容"视图，以独立卡片形式展示，内部使用 {@link SubTabContainer} 支持多实例子 tab。
 */
@Slf4j
public class EditorPanelController implements Initializable {

    public enum ViewType { FILE, TODO }

    @FXML
    @Getter
    private VBox editorPanel;
    @FXML
    protected StackPane viewContainer;

    // ===== 视图管理 =====
    protected final ObservableList<EditorTab> tabs = FXCollections.observableArrayList();
    @Getter
    protected EditorTab activeTab = null;
    /** 进入当前视图之前的活跃视图（关闭当前视图时用于回落） */
    private EditorTab previousActiveTab = null;
    private int tabIdCounter = 0;

    @Setter
    @Getter
    protected IndexController indexController;

    @Getter
    protected ViewType currentViewType = null;

    /** 当前视图类型变化时的回调 */
    @Setter
    protected Consumer<ViewType> onViewTypeChanged;

    /** 视图类型变化后触发回调 */
    private void notifyViewTypeChanged() {
        if (onViewTypeChanged != null) {
            onViewTypeChanged.accept(currentViewType);
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 让 editorPanel 填满 slot 容器
        editorPanel.setMaxWidth(Double.MAX_VALUE);
        editorPanel.setMaxHeight(Double.MAX_VALUE);
        setupRoundedClip();
    }

    private void setupRoundedClip() {
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(viewContainer.widthProperty());
        clip.heightProperty().bind(viewContainer.heightProperty());
        clip.setArcWidth(24);
        clip.setArcHeight(24);
        viewContainer.setClip(clip);
    }

    // ===== 视图管理核心方法 =====

    /**
     * 当前展示的视图是否是指定类型。
     */
    public boolean isCurrentView(ViewType type) {
        return currentViewType == type;
    }

    /**
     * 创建视图卡片：用 StackPane 包装内容并恢复其可见性。
     */
    protected EditorTab createTab(ViewType type, Node content) {
        String id = type.name().toLowerCase() + "-" + (tabIdCounter++);
        StackPane card = wrapWithCloseButton(content);
        return new EditorTab(id, type, content, card, new HashMap<>());
    }

    /**
     * 用 StackPane 包装内容。
     */
    private StackPane wrapWithCloseButton(Node content) {
        StackPane card = new StackPane();
        card.getStyleClass().add("editor-panel__card-wrapper");

        // 内容填充整个卡片
        if (content instanceof Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            region.setMaxHeight(Double.MAX_VALUE);
        }
        content.setVisible(true);
        content.setManaged(true);
        card.getChildren().add(content);

        return card;
    }

    /**
     * 添加视图到容器
     */
    protected void addTab(EditorTab tab) {
        tabs.add(tab);
        if (!viewContainer.getChildren().contains(tab.card)) {
            viewContainer.getChildren().add(tab.card);
        }
        tab.card.setMaxWidth(Double.MAX_VALUE);
        tab.card.setMaxHeight(Double.MAX_VALUE);
        tab.card.setVisible(false);
        tab.card.setManaged(false);
    }

    /**
     * 切换到指定视图
     */
    protected void selectTab(EditorTab tab) {
        if (activeTab != tab) {
            previousActiveTab = activeTab;
        }
        for (EditorTab t : tabs) {
            boolean active = (t == tab);
            t.card.setVisible(active);
            t.card.setManaged(active);
        }
        activeTab = tab;
        currentViewType = tab.viewType;
        notifyViewTypeChanged();
    }

    /**
     * 关闭视图
     */
    protected void closeTab(EditorTab tab) {
        tabs.remove(tab);
        viewContainer.getChildren().remove(tab.card);
        onTabClosed(tab);
        if (activeTab == tab) {
            if (tabs.isEmpty()) {
                activeTab = null;
                currentViewType = null;
                notifyViewTypeChanged();
                if (indexController != null) {
                    indexController.closeEditorPanel();
                } else {
                    hide();
                }
            } else if (previousActiveTab != null && tabs.contains(previousActiveTab)) {
                selectTab(previousActiveTab);
            } else {
                activeTab = null;
                currentViewType = null;
                notifyViewTypeChanged();
                if (indexController != null) {
                    indexController.closeEditorPanel();
                } else {
                    hide();
                }
            }
        }
    }

    /**
     * 子类 hook：视图关闭时清理资源
     */
    protected void onTabClosed(EditorTab tab) {
        // 基类空实现
    }

    protected EditorTab findTabByType(ViewType type) {
        return tabs.stream().filter(t -> t.viewType == type).findFirst().orElse(null);
    }

    protected EditorTab findTabById(String id) {
        return tabs.stream().filter(t -> t.id.equals(id)).findFirst().orElse(null);
    }

    protected void closeTabById(String id) {
        EditorTab tab = findTabById(id);
        if (tab != null) {
            closeTab(tab);
        }
    }

    // ===== 视图切换 =====

    public void showFileView() {
        // work 模式不支持文件内容视图
    }

    // ===== 面板显示/隐藏 =====

    public void show() {
        editorPanel.setVisible(true);
        editorPanel.setManaged(true);
    }

    public void hide() {
        editorPanel.setVisible(false);
        editorPanel.setManaged(false);
    }

    public boolean isVisible() {
        return editorPanel.isVisible();
    }

    // ===== Todo 视图（每 session 一个 tab，work/code 模式共用） =====

    /** EditorTab.userData 中记录 todo 所属 sessionId 的 key */
    private static final String TODO_SESSION_KEY = "sessionId";

    /**
     * 展示/更新指定 session 的 Todo 视图：首次调用创建该 session 专属的 TODO tab，
     * 后续更新复用同一 tab 原地刷新。仅当该 session 是当前 active session 时才切到前台显示；
     * 非 active session 的更新只刷新 tab 内容（保持隐藏，切回时恢复显示最新内容）。
     */
    public void showTodoView(String sessionId, String todosJson) {
        boolean isActive = java.util.Objects.equals(Store.currentSessionId.get(), sessionId);
        EditorTab todoTab = findTodoTabBySession(sessionId);
        if (todoTab == null) {
            cn.bitloom.node.tool.TodoCard card = new cn.bitloom.node.tool.TodoCard(todosJson, this::closeActiveTodoView);
            card.getStyleClass().add("editor-panel__todo-view");

            final EditorTab newTab = createTab(ViewType.TODO, card);
            newTab.userData.put(TODO_SESSION_KEY, sessionId);
            addTab(newTab);
            // 宽度约束绑定到视图容器：阻断超长 item 文本把卡片/行撑出可视区，
            // 使长文本正常换行，而 activeForm 与状态标签始终可见
            card.maxWidthProperty().bind(viewContainer.widthProperty());
            if (isActive) {
                revealEditorPanel();
                selectTab(newTab);
            }
        } else {
            ((cn.bitloom.node.tool.TodoCard) todoTab.content).update(todosJson);
            if (isActive) {
                revealEditorPanel();
                selectTab(todoTab);
            }
        }
    }

    /** active session 的 todo 显示前确保面板已挂载可见（面板被手动关闭后重新挂回） */
    private void revealEditorPanel() {
        if (indexController != null) {
            indexController.ensureEditorVisible();
        }
        show();
    }

    /**
     * session 激活时恢复其 Todo 视图：有该 session 的 tab 则切换显示，
     * 没有且当前正显示其他 session 的 todo 时退出 todo 显示（tab 保留，切回可恢复）。
     */
    public void restoreTodoForSession(String sessionId) {
        EditorTab target = findTodoTabBySession(sessionId);
        if (target != null) {
            if (indexController != null) {
                indexController.ensureEditorVisible();
            }
            selectTab(target);
        } else {
            collapseActiveTodoView();
        }
    }

    /** 查找指定 session 的 Todo tab */
    private EditorTab findTodoTabBySession(String sessionId) {
        return tabs.stream()
                .filter(t -> t.viewType == ViewType.TODO)
                .filter(t -> java.util.Objects.equals(t.userData.get(TODO_SESSION_KEY), sessionId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 关闭当前正在显示的 Todo 视图（TodoCard 关闭按钮回调；该卡片所属 session 的 tab 一并销毁）。
     */
    public void closeActiveTodoView() {
        if (activeTab != null && activeTab.viewType == ViewType.TODO) {
            closeTodoTab(activeTab);
        }
    }

    /**
     * 若当前正显示 Todo 视图则退出（回落到上一个非 todo 视图或关闭面板），
     * 所有 session 的 todo tab 保留在 tabs 中以便切回时恢复。
     */
    private void collapseActiveTodoView() {
        if (activeTab == null || activeTab.viewType != ViewType.TODO) {
            return;
        }
        if (previousActiveTab != null && tabs.contains(previousActiveTab)
                && previousActiveTab != activeTab && previousActiveTab.viewType != ViewType.TODO) {
            selectTab(previousActiveTab);
        } else {
            activeTab = null;
            currentViewType = null;
            notifyViewTypeChanged();
            if (indexController != null) {
                indexController.closeEditorPanel();
            } else {
                hide();
            }
        }
    }

    private void closeTodoTab(EditorTab tab) {
        if (tab.content instanceof cn.bitloom.node.tool.TodoCard card
                && card.maxWidthProperty().isBound()) {
            card.maxWidthProperty().unbind();
        }
        closeTab(tab);
    }

    // ===== coder 专有方法（通用基类空实现，coder 模式 override） =====

    public void showFileContent(Path filePath) {
        // work 模式不支持文件内容显示
    }

    // ===== 视图数据结构 =====

    protected static class EditorTab {
        final String id;
        final ViewType viewType;
        final Node content;
        final StackPane card;
        final Map<String, Object> userData;

        EditorTab(String id, ViewType viewType, Node content, StackPane card, Map<String, Object> userData) {
            this.id = id;
            this.viewType = viewType;
            this.content = content;
            this.card = card;
            this.userData = userData;
        }
    }
}
