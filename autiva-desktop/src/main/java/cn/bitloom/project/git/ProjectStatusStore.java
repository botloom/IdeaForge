package cn.bitloom.project.git;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 当前展示项目的 Git 状态共享存储。
 * 持有项目根、文件状态映射与含改动目录集合，并对外暴露布尔翻转刷新信号
 * （风格与 Store.refreshHistory 一致），供目录树与文件视图订阅刷新。
 */
public class ProjectStatusStore {

    private final GitStatusService gitStatusService;

    private volatile Path projectRoot;
    private volatile Map<Path, GitFileStatus> statusMap = Map.of();
    private volatile Set<Path> changedDirs = Set.of();

    /** 状态变更刷新信号，翻转触发 UI 刷新 */
    public final BooleanProperty refreshSignal = new SimpleBooleanProperty(false);

    public ProjectStatusStore(GitStatusService gitStatusService) {
        this.gitStatusService = gitStatusService;
    }

    public Path getProjectRoot() {
        return projectRoot;
    }

    public Map<Path, GitFileStatus> getStatusMap() {
        return statusMap;
    }

    public Set<Path> getChangedDirs() {
        return changedDirs;
    }

    /**
     * 由状态源（打开项目 / 文件监听触发）注入新状态并发送刷新信号。
     * 根与状态映射均未变化时不翻转刷新信号——避免进入目录树等场景下
     * 无意义的状态刷新触发整树/编辑器重绘。
     */
    public void update(Path root, Map<Path, GitFileStatus> statusMap) {
        Map<Path, GitFileStatus> newMap = statusMap != null ? statusMap : Map.of();
        if (Objects.equals(root, this.projectRoot) && Objects.equals(newMap, this.statusMap)) {
            return;
        }
        this.projectRoot = root;
        this.statusMap = newMap;
        this.changedDirs = gitStatusService.collectChangedDirs(newMap);
        this.refreshSignal.set(!this.refreshSignal.get());
    }

    /**
     * 查询文件/文件夹的 Git 状态，未在 map 中（如无改动或递归路径被截断）返回 null。
     */
    public GitFileStatus statusOf(Path item) {
        if (item == null) {
            return null;
        }
        return statusMap.get(item.toAbsolutePath().normalize());
    }

    /**
     * 目录内是否存在改动。
     */
    public boolean isDirChanged(Path item) {
        if (item == null) {
            return false;
        }
        return changedDirs.contains(item.toAbsolutePath().normalize());
    }
}
