package cn.bitloom.node.project;

import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 延迟加载的文件树节点。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>状态机（NOT_LOADED → LOADING → LOADED）显式管理加载生命周期，
 *       配合 rescanPending 标志合并加载期间到达的刷新请求，杜绝"追加式挂载"
 *       与"未加载节点被 rescan 填充后再被展开加载"两类重复节点来源</li>
 *   <li>所有子节点变更（首次加载与增量重扫）统一走 {@link #applyChildren}：
 *       以扫描快照为准做 diff 合并后 {@code setAll} 整体替换，幂等操作，
 *       无论此前 children 处于何种状态，执行后都与文件系统快照严格一致</li>
 *   <li>共享单线程扫描执行器：串行化磁盘 IO，避免"每个节点一个线程"的
 *       创建风暴与并发随机读反而拖慢扫描</li>
 *   <li>目录性由 {@link FileEntry} 在扫描时捕获，{@link #isLeaf()} 零 IO，
 *       保证 JavaFX 布局线程不做文件系统调用</li>
 * </ul>
 */
@Slf4j
public class LazyTreeItem extends TreeItem<FileEntry> {

    /** 共享扫描执行器：所有节点的目录扫描串行执行（守护线程，随 JVM 退出） */
    private static final ExecutorService SCAN_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "autiva-tree-scan");
        t.setDaemon(true);
        return t;
    });

    /** 后台线程执行：扫描给定目录的子条目（含过滤排序），返回待挂载的子节点条目。 */
    private final Function<Path, List<FileEntry>> scanChildren;
    private final Function<FileEntry, LazyTreeItem> childFactory;

    private enum LoadState { NOT_LOADED, LOADING, LOADED }

    private LoadState state = LoadState.NOT_LOADED;
    /** 加载进行中到达的 rescan 请求，待加载完成后立即补扫一次。 */
    private boolean rescanPending = false;

    public LazyTreeItem(FileEntry entry,
                        Function<Path, List<FileEntry>> scanChildren,
                        Function<FileEntry, LazyTreeItem> childFactory) {
        super(entry);
        this.scanChildren = scanChildren;
        this.childFactory = childFactory;
        expandedProperty().addListener((obs, wasExpanded, isNowExpanded) -> {
            if (!isNowExpanded) {
                return;
            }
            if (state == LoadState.NOT_LOADED) {
                ensureLoaded();
            } else if (state == LoadState.LOADED) {
                // 折叠后再展开：目录内容可能已过期，后台补扫为最新快照
                rescan();
            }
        });
    }

    /** 首次加载子节点：后台扫描，完成后在 FX 线程统一 diff 挂载。 */
    private void ensureLoaded() {
        if (state != LoadState.NOT_LOADED) {
            return;
        }
        state = LoadState.LOADING;
        submitScan(entries -> {
            applyChildren(entries);
            state = LoadState.LOADED;
            if (rescanPending) {
                rescanPending = false;
                rescan();
            }
        });
    }

    /**
     * 增量重扫本目录并 diff 同步 children。
     * <ul>
     *   <li>NOT_LOADED：跳过——节点尚未展开过，首次展开时会扫描最新文件系统</li>
     *   <li>LOADING：仅记录 pending，加载完成后立即补扫，避免并发扫描同一目录</li>
     *   <li>LOADED：后台重扫 + FX 线程 diff 同步</li>
     * </ul>
     */
    public void rescan() {
        switch (state) {
            case NOT_LOADED -> { /* 展开时自然加载最新 */ }
            case LOADING -> rescanPending = true;
            case LOADED -> submitScan(this::applyChildren);
        }
    }

    /** 提交后台扫描任务，完成后在 FX 线程回调（回调内已完成线程切换）。 */
    private void submitScan(Consumer<List<FileEntry>> onDone) {
        Path dir = getValue().path();
        SCAN_EXECUTOR.execute(() -> {
            List<FileEntry> children = scanQuietly(dir);
            Platform.runLater(() -> onDone.accept(children));
        });
    }

    /** 扫描目录，失败时返回空列表（后台线程调用，异常不外抛）。 */
    private List<FileEntry> scanQuietly(Path dir) {
        try {
            return scanChildren.apply(dir);
        } catch (Exception e) {
            log.warn("扫描目录失败: {}", dir, e);
            return List.of();
        }
    }

    /**
     * FX 线程：以扫描快照为准 diff 同步 children。
     * 仍存在的子节点复用原 LazyTreeItem 实例（展开/选中状态天然保留），
     * 消失的移除、新增的创建，最后 setAll 整体替换——幂等，
     * 不依赖调用前 children 的状态，重复调用不可能产生重复节点。
     * <p>
     * 快照与当前 children 完全一致（同序同实例）时跳过 setAll：
     * 内容相同的 setAll 仍会触发列表变更事件，导致 TreeView 重建全部可见行，
     * 表现为进入目录树或无变化重扫时的整树闪烁。
     */
    private void applyChildren(List<FileEntry> snapshot) {
        List<TreeItem<FileEntry>> current = getChildren();
        Map<String, LazyTreeItem> existingByKey = new HashMap<>();
        for (TreeItem<FileEntry> child : current) {
            if (child instanceof LazyTreeItem lazy && lazy.getValue() != null) {
                existingByKey.put(lazy.getValue().name(), lazy);
            }
        }
        List<TreeItem<FileEntry>> merged = new ArrayList<>(snapshot.size());
        for (FileEntry entry : snapshot) {
            LazyTreeItem existing = existingByKey.get(entry.name());
            merged.add(existing != null ? existing : childFactory.apply(entry));
        }
        if (sameChildren(current, merged)) {
            return;
        }
        getChildren().setAll(merged);
    }

    /** 两个 children 列表是否同序且元素实例完全相同（无任何增删/重排）。 */
    private static boolean sameChildren(List<TreeItem<FileEntry>> current,
                                        List<TreeItem<FileEntry>> merged) {
        if (current.size() != merged.size()) {
            return false;
        }
        for (int i = 0; i < merged.size(); i++) {
            if (current.get(i) != merged.get(i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isLeaf() {
        // 目录性由扫描时捕获的 FileEntry 提供，零文件系统 IO
        return !getValue().directory();
    }
}
