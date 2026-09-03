package cn.bitloom.project;

import cn.bitloom.agentic.tool.ToolUtils;
import cn.bitloom.node.project.FileEntry;
import cn.bitloom.node.project.LazyTreeItem;
import javafx.scene.control.TreeItem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件树服务
 * 构建项目目录树，使用 LazyTreeItem 实现延迟加载和正确的目录展开行为。
 * 子节点扫描（文件系统 IO）在共享后台线程串行执行，避免阻塞 UI 线程。
 */
public class FileTreeService {

    /**
     * 构建文件树
     *
     * @param rootPath 项目根路径
     * @return TreeItem 根节点（setExpanded(true) 触发首次子节点后台扫描）
     */
    public TreeItem<FileEntry> buildFileTree(Path rootPath) {
        LazyTreeItem rootItem = createLazyItem(new FileEntry(rootPath, true));
        rootItem.setExpanded(true);
        return rootItem;
    }

    /** 创建懒加载节点，递归绑定后台扫描函数与子节点工厂。 */
    private LazyTreeItem createLazyItem(FileEntry entry) {
        return new LazyTreeItem(entry, this::scanChildren, this::createLazyItem);
    }

    /**
     * 后台线程执行：单次遍历完成忽略过滤与目录分类（每个条目仅一次 stat 调用），
     * 排序直接使用已捕获的目录标志（目录优先 + 文件名大小写不敏感），
     * 避免排序比较器反复触发文件系统调用。
     */
    private List<FileEntry> scanChildren(Path parentPath) {
        if (!Files.isDirectory(parentPath)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(parentPath)) {
            return stream
                    .filter(p -> !ToolUtils.isIgnoredPath(p))
                    .map(p -> new FileEntry(p, Files.isDirectory(p)))
                    .sorted(Comparator
                            .comparing((FileEntry e) -> !e.directory())
                            .thenComparing(FileEntry::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
