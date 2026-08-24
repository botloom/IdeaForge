package cn.bitloom.project;

import cn.bitloom.agentic.tool.ToolUtils;
import cn.bitloom.node.project.LazyTreeItem;
import javafx.scene.control.TreeItem;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 文件树服务
 * 构建项目目录树，使用 LazyTreeItem 实现延迟加载和正确的目录展开行为。
 * 子节点扫描（文件系统 IO）在后台线程执行，避免阻塞 UI 线程。
 */
@Component
public class FileTreeService {

    /**
     * 构建文件树
     *
     * @param rootPath 项目根路径
     * @return TreeItem 根节点
     */
    public TreeItem<Path> buildFileTree(Path rootPath) {
        LazyTreeItem rootItem = createLazyItem(rootPath);
        rootItem.setExpanded(true);
        return rootItem;
    }

    /** 创建懒加载节点，递归绑定后台扫描函数与子节点工厂。 */
    private LazyTreeItem createLazyItem(Path path) {
        return new LazyTreeItem(path, this::scanSortedChildren, this::createLazyItem);
    }

    /**
     * 后台线程执行：扫描目录并返回排序、过滤后的子路径列表。
     * 注意：子节点实际 Path 在挂载时由 LazyTreeItem 通过扫描结果确定；
     * 此处返回的 Path 即为待挂载子节点。
     */
    private List<Path> scanSortedChildren(Path parentPath) {
        if (!Files.isDirectory(parentPath)) {
            return List.of();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(parentPath)) {
            stream.sorted(Comparator
                            .comparing((Path p) -> !Files.isDirectory(p))
                            .thenComparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(child -> {
                        if (!ToolUtils.isIgnoredPath(child)) {
                            result.add(child);
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }
        return result;
    }
}
