package cn.bitloom.node.project;

import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 延迟加载的文件树节点。
 * <p>
 * 重写 {@link #isLeaf()} 基于文件系统实际类型判断，确保目录节点始终显示展开箭头。
 * 首次展开时的子节点扫描（文件系统 IO）在后台线程执行，完成后切回 JavaFX 线程挂载子节点，
 * 避免展开大目录时阻塞 UI 线程。
 */
@Slf4j
public class LazyTreeItem extends TreeItem<Path> {

    /** 后台线程执行：扫描给定目录的子路径列表（含过滤），返回待挂载的子节点绝对路径或文件名 Path。 */
    private final Function<Path, List<Path>> scanChildren;
    private final Function<Path, LazyTreeItem> childFactory;
    private boolean loaded = false;
    private boolean loading = false;

    public LazyTreeItem(Path path, Function<Path, List<Path>> scanChildren, Function<Path, LazyTreeItem> childFactory) {
        super(path);
        this.scanChildren = scanChildren;
        this.childFactory = childFactory;
        expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
            if (isNowExpanded && !loaded && !loading) {
                loadChildren();
            }
        });
    }

    private void loadChildren() {
        loading = true;
        loaded = true;
        Path parentPath = getValue();
        // 后台线程扫描文件系统，避免阻塞 FX 线程
        Thread worker = new Thread(() -> {
            List<Path> children;
            try {
                children = scanChildren.apply(parentPath);
            } catch (Exception e) {
                log.warn("扫描目录失败: {}", parentPath, e);
                children = List.of();
            }
            final List<Path> snapshot = children;
            Platform.runLater(() -> mountChildren(snapshot));
        }, "autiva-tree-scan");
        worker.setDaemon(true);
        worker.start();
    }

    /** 在 FX 线程创建子节点并挂载到 children。 */
    private void mountChildren(List<Path> snapshot) {
        if (snapshot.isEmpty()) {
            return;
        }
        for (Path child : snapshot) {
            getChildren().add(childFactory.apply(child));
        }
    }

    /**
     * 重新扫描本目录并在不重建节点实例的前提下同步 children 与文件系统：
     * 仍存在的子节点复用原 LazyTreeItem 实例（展开/选中状态天然保留），
     * 消失的子节点被移除，新增的追加。仅在后台合并加载后于 FX 线程执行 diff。
     * 未加载（尚未展开）的节点无需处理——首次展开时会扫描最新文件系统。
     */
    public void rescan() {
        if (loading) {
            // 首次扫描尚未落地：其挂载完成后 children 即当时快照；新变化由后续去抖刷新的再次 rescan 补齐
            return;
        }
        Path parentPath = getValue();
        Thread worker = new Thread(() -> {
            List<Path> children;
            try {
                children = scanChildren.apply(parentPath);
            } catch (Exception e) {
                log.warn("重扫目录失败: {}", parentPath, e);
                return;
            }
            final List<Path> snapshot = children;
            Platform.runLater(() -> syncChildren(snapshot));
        }, "autiva-tree-rescan");
        worker.setDaemon(true);
        worker.start();
    }

    /** 在 FX 线程进行 children diff 同步，保留同路径节点实例。 */
    private void syncChildren(List<Path> snapshot) {
        if (snapshot.isEmpty()) {
            getChildren().clear();
            return;
        }
        Map<String, LazyTreeItem> existingByKey = new HashMap<>();
        for (TreeItem<Path> child : getChildren()) {
            if (child.getValue() != null) {
                existingByKey.put(child.getValue().getFileName().toString(), (LazyTreeItem) child);
            }
        }
        List<TreeItem<Path>> merged = new ArrayList<>(snapshot.size());
        for (Path newPath : snapshot) {
            String key = newPath.getFileName().toString();
            LazyTreeItem existing = existingByKey.get(key);
            if (existing != null) {
                merged.add(existing);
            } else {
                merged.add(childFactory.apply(newPath));
            }
        }
        getChildren().setAll(merged);
    }

    @Override
    public boolean isLeaf() {
        Path p = getValue();
        if (p == null) {
            return true;
        }
        try {
            return !Files.isDirectory(p);
        } catch (Exception e) {
            return true;
        }
    }
}
