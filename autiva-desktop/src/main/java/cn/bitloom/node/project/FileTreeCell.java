package cn.bitloom.node.project;

import cn.bitloom.node.FileIconResolver;
import cn.bitloom.node.svg.SvgImageView;
import cn.bitloom.project.git.GitFileStatus;
import cn.bitloom.project.git.ProjectStatusStore;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件树单元格
 * 显示文件名和图标（文件夹 / 文件类型图标）
 * 使用 SvgImageView 加载 SVG 图标，符合 Apple 设计规范。
 *
 * <p>性能设计（高频渲染路径零 IO、零转码）：
 * <ul>
 *   <li>图标节点复用单个实例，updateItem 仅切换 SVG 路径，
 *       命中 {@link SvgImageView} 的静态图片缓存，无 Batik 转码开销</li>
 *   <li>目录性/文件名全部读取 {@link FileEntry} 缓存标志，
 *       不做任何文件系统调用（原实现的 Files.isDirectory/Files.exists
 *       会在每次单元格重绘时阻塞 FX 线程）</li>
 * </ul>
 * <p>Git 状态着色依赖外部设置的 {@link ProjectStatusStore}（全局共享状态），无则忽略。
 */
@Slf4j
public class FileTreeCell extends TreeCell<FileEntry> {

    private static final double ICON_SIZE = 16;

    private static final List<String> ALL_STYLE_CLASSES = List.of(
            "file-tree__folder", "file-tree__file",
            "file-tree__file--code", "file-tree__file--data",
            "file-tree__file--md", "file-tree__file--text", "file-tree__file--image",
            "file-tree__file--git-added", "file-tree__file--git-modified",
            "file-tree__file--git-untracked", "file-tree__folder--git-changed"
    );

    /** 全局共享的 Git 状态存储，由 SideBarController 设置 */
    private static ProjectStatusStore statusStore;

    /** 复用图标节点：仅切换 SVG 路径（内部图片缓存命中，切换零开销） */
    private final SvgImageView icon = new SvgImageView();

    /**
     * 设置全局 Git 状态存储（SideBarController 打开项目时注入）。
     */
    public static void setStatusStore(ProjectStatusStore store) {
        statusStore = store;
    }

    // hover 高亮设计（索引驱动，不使用 CSS :hover 伪类）：
    // :hover 伪类由 mouseEntered/Exited 事件维护；展开/折叠时 VirtualFlow 复用/重排 cell，
    // 事件跨帧到达，伪类瞬时丢失再恢复——表现为点击折叠箭头时灰影闪一下。
    // 改为把"hover 行索引"记录在 TreeView properties 上的共享属性中：
    //   - cell hover=true 时写入自己当前索引；hover=false 且仍显示该索引行时清除
    //     （cell 被复用后 getIndex() 已变，不会误清）
    //   - 样式类按"索引 == hover 行索引"判定，接管该行的新 cell 立即命中索引恢复高亮，
    //     无视觉间隙；选中行不加 hover 类（蓝高亮优先）
    private static final String HOVER_CLASS = "file-tree__cell--hover";
    private static final String HOVERED_INDEX_KEY = "fileTreeHoveredIndex";

    private IntegerProperty hoveredIndex;

    public FileTreeCell() {
        icon.setFitWidth(ICON_SIZE);
        icon.setFitHeight(ICON_SIZE);
        hoverProperty().addListener((obs, wasHover, isHover) -> {
            IntegerProperty idx = hoveredIndexOf();
            if (idx == null) {
                return;
            }
            if (isHover) {
                idx.set(getIndex());
            } else if (idx.get() == getIndex()) {
                idx.set(-1);
            }
        });
        indexProperty().addListener(obs -> applyHoverClass());
        selectedProperty().addListener(obs -> applyHoverClass());
    }

    /** 惰性挂接并获取本树共享的 hover 行索引属性（同树所有 cell 共享一份）。 */
    private IntegerProperty hoveredIndexOf() {
        if (hoveredIndex == null) {
            TreeView<FileEntry> tree = getTreeView();
            if (tree == null) {
                return null;
            }
            hoveredIndex = (IntegerProperty) tree.getProperties().computeIfAbsent(
                    HOVERED_INDEX_KEY, k -> new SimpleIntegerProperty(-1));
            hoveredIndex.addListener(obs -> applyHoverClass());
        }
        return hoveredIndex;
    }

    /** 按"索引 == hover 行索引且未选中"刷新 hover 高亮样式类。 */
    private void applyHoverClass() {
        IntegerProperty idx = hoveredIndex;
        boolean should = idx != null && getIndex() >= 0
                && getIndex() == idx.get() && !isSelected();
        if (should) {
            if (!getStyleClass().contains(HOVER_CLASS)) {
                getStyleClass().add(HOVER_CLASS);
            }
        } else {
            getStyleClass().remove(HOVER_CLASS);
        }
    }

    @Override
    protected void updateItem(FileEntry item, boolean empty) {
        super.updateItem(item, empty);
        hoveredIndexOf(); // 惰性挂接共享 hover 索引属性（此时 getTree() 已可用）

        if (empty || item == null) {
            setText(null);
            setGraphic(null);
            getStyleClass().removeAll(ALL_STYLE_CLASSES);
            getStyleClass().remove(HOVER_CLASS);
            setOnDragDetected(null);
            return;
        }

        String fileName = item.name();
        setText(fileName);
        icon.setSvgPath(item.directory()
                ? FileIconResolver.folderIconPath()
                : FileIconResolver.resolveIconPath(fileName));
        setGraphic(icon);

        refreshStyleClasses(item, fileName);
        applyHoverClass();

        // 文件与文件夹均可拖拽到对话框（路径在拖拽发起时使用，渲染阶段零 IO）
        java.nio.file.Path path = item.path();
        setOnDragDetected(event -> {
            Dragboard db = startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(List.of(path.toFile()));
            db.setContent(content);
            event.consume();
        });
    }

    /**
     * 状态刷新信号回调：仅重算当前条目的 Git 着色样式类。
     * 与 {@code TreeView.refresh()}（销毁并重建全部可见单元格，表现为整树闪烁）相对，
     * 本方法不重建单元格节点，只做样式类 diff。
     */
    public void refreshGitStyles() {
        FileEntry item = getItem();
        if (item == null) {
            return;
        }
        refreshStyleClasses(item, item.name());
    }

    private void refreshStyleClasses(FileEntry item, String fileName) {
        List<String> target = computeStyleClasses(item, fileName);
        // 无变化跳过：着色未变时不触发样式表变更事件，避免无谓的 CSS 重算与重绘
        List<String> current = new ArrayList<>(getStyleClass());
        current.retainAll(ALL_STYLE_CLASSES);
        if (current.equals(target)) {
            return;
        }
        getStyleClass().removeAll(ALL_STYLE_CLASSES);
        getStyleClass().addAll(target);
    }

    /** 计算当前条目应具备的类型/Git 样式类（固定顺序，供无变化比对）。 */
    private List<String> computeStyleClasses(FileEntry item, String fileName) {
        List<String> classes = new ArrayList<>(4);
        if (item.directory()) {
            classes.add("file-tree__folder");
            if (statusStore != null && statusStore.isDirChanged(item.path())) {
                classes.add("file-tree__folder--git-changed");
            }
            return classes;
        }
        classes.add("file-tree__file");
        String iconPath = FileIconResolver.resolveIconPath(fileName);
        if (iconPath.endsWith("file-code.svg")) {
            classes.add("file-tree__file--code");
        } else if (iconPath.endsWith("file-data.svg")) {
            classes.add("file-tree__file--data");
        } else if (iconPath.endsWith("file-md.svg")) {
            classes.add("file-tree__file--md");
        } else if (iconPath.endsWith("file-text.svg")) {
            classes.add("file-tree__file--text");
        } else if (iconPath.endsWith("file-image.svg")) {
            classes.add("file-tree__file--image");
        }
        if (statusStore != null) {
            GitFileStatus st = statusStore.statusOf(item.path());
            if (st != null) {
                classes.add(switch (st) {
                    case ADDED -> "file-tree__file--git-added";
                    case MODIFIED -> "file-tree__file--git-modified";
                    case UNTRACKED -> "file-tree__file--git-untracked";
                });
            }
        }
        return classes;
    }
}
