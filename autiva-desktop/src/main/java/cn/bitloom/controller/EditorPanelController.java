package cn.bitloom.controller;

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
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

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
@Component
@Primary
public class EditorPanelController implements Initializable {

    public enum ViewType { FILE }

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
