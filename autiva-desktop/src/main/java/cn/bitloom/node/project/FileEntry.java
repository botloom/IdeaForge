package cn.bitloom.node.project;

import java.nio.file.Path;

/**
 * 文件树节点条目：路径 + 后台扫描时捕获的目录标志。
 * <p>
 * 目录性在扫描线程确定一次并随节点缓存，渲染线程（单元格着色、isLeaf、图标选择）
 * 全部读取该标志，彻底消除 FX 线程上的文件系统 IO。
 */
public record FileEntry(Path path, boolean directory) {

    /** 文件名（根路径等无文件名的路径回退到完整路径字符串）。 */
    public String name() {
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : path.toString();
    }
}
