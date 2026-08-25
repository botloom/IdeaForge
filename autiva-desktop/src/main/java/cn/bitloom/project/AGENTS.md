# Project 包

## 概述
本包实现了项目管理系统，用于项目注册、查询和 Git 信息读取，供 coder 模式下的项目管理功能使用。

## 核心类

### ProjectInfo
项目信息数据模型（record 类型）。

**字段：**
- `id`: 项目唯一ID（UUID）
- `name`: 项目名称
- `path`: 项目本地路径
- `gitBranch`: 当前 Git 分支（可为 null）
- `createdAt`: 创建时间（Instant）

### ProjectRegistry
项目注册表管理器（Spring `@Component`），管理已注册的项目列表。

**持久化：** `~/.autiva/projects/registry.json`

**核心方法：**
- `List<ProjectInfo> listProjects()`: 列出所有已注册项目
- `Optional<ProjectInfo> findById(String id)`: 根据 ID 查找项目
- `ProjectInfo createProject(String name)`: 创建新项目（创建空目录并注册）
- `ProjectInfo registerLocal(String path, String name)`: 注册本地文件夹为项目
- `void removeProject(String id)`: 移除项目（仅从注册表移除，不删除文件）
- `ProjectInfo refreshBranch(String id)`: 刷新项目的 Git 分支信息

**设计：**
- 使用 `CopyOnWriteArrayList` 保证线程安全
- 每次修改后自动持久化到磁盘
- 构造时自动从磁盘加载

### GitService
Git 服务（Spring `@Component`），仅查询 Git 信息，不支持修改操作。

**核心方法：**
- `Optional<String> getCurrentBranch(Path projectPath)`: 获取指定路径的当前 Git 分支
- `boolean isGitRepository(Path projectPath)`: 检查路径是否是 Git 仓库

**实现：**
- 通过执行 `git rev-parse --abbrev-ref HEAD` 命令获取分支
- 5 秒超时保护
- 自动检测 .git 目录存在性

### FileTreeService
文件树构建服务（Spring `@Component`），为编辑器面板构建项目目录树。使用 `LazyTreeItem`（位于 `cn.bitloom.node.project` 包）替代原生 `TreeItem`，实现正确的目录展开行为和懒加载。

**核心方法：**
- `TreeItem<FileEntry> buildFileTree(Path rootPath)`: 构建文件树根节点（`LazyTreeItem`），并通过 `setExpanded(true)` 触发首次子节点后台扫描
- `private List<FileEntry> scanChildren(Path parentPath)`: 后台线程扫描目录，单次遍历完成忽略过滤（`ToolUtils.isIgnoredPath`）与目录分类（每个条目仅一次 `Files.isDirectory` stat 调用），排序使用已捕获的目录标志（目录优先 + 文件名字母序大小写不敏感），避免排序比较器反复触发文件系统调用

**相关类：FileEntry**（`cn.bitloom.node.project.FileEntry`）

record（`path` + `directory`）。目录性在扫描线程确定一次并随节点缓存，渲染线程（单元格着色、`isLeaf`、图标选择）全部读取该标志，FX 线程零文件系统 IO。

**相关类：LazyTreeItem**（`cn.bitloom.node.project.LazyTreeItem`）

继承 `javafx.scene.control.TreeItem<FileEntry>`，解决 JavaFX 默认 `TreeItem` 在懒加载场景下深层目录无法展开的问题。

**核心机制：**
- 状态机 `NOT_LOADED → LOADING → LOADED` 显式管理加载生命周期；`rescanPending` 标志合并加载期间到达的刷新请求，加载完成后立即补扫
- 所有子节点变更（首次加载与增量重扫）统一走 `applyChildren`：以扫描快照为准 diff 合并（同文件名复用原节点实例，保留展开/选中状态）后 `setAll` 整体替换——幂等操作，无论调用前 children 处于何种状态，执行后都与文件系统快照严格一致，杜绝重复节点；快照与当前 children 同序同实例（无变化）时跳过 `setAll`，避免内容相同的列表变更事件触发 TreeView 重建可见行造成闪烁
- `rescan()`：NOT_LOADED 跳过（展开时自然扫描最新）；LOADING 记 pending；LOADED 后台重扫
- 展开监听：首次展开触发加载；折叠后再展开（LOADED）自动补扫最新快照
- 共享单线程扫描执行器（`autiva-tree-scan` 守护线程）：所有节点的目录扫描串行执行，避免"每节点一线程"的创建风暴与并发随机读
- 重写 `isLeaf()`：返回 `!getValue().directory()`，基于扫描时捕获的标志判断，零 IO 且与 children 加载状态解耦

**问题根因（使用原生 TreeItem 的死锁）：**
原生 `TreeItem.isLeaf()` 默认基于 `getChildren().isEmpty()` 判断。在懒加载场景下，非根目录的 children 尚未加载（为空），因此被误判为叶子节点 → TreeView 不渲染展开箭头 → 用户无法点击展开 → `expandedProperty` 监听器无法触发 → children 永远为空。`LazyTreeItem` 通过将 `isLeaf()` 与 children 状态解耦打破此死锁。

**优点：**
- Repository 模式：ProjectRegistry 管理项目列表的增删改查
- 服务模式：GitService 封装 Git 命令调用
- 持久化模式：JSON 文件持久化
- 懒加载模式：FileTreeService + LazyTreeItem 实现文件树按需加载，避免一次性加载大型项目所有文件

## Git 状态着色子包（`cn.bitloom.project.git`）

用于目录树与文件内容视图的 Git 工作区状态显示（新增绿 / 修改蓝 / 未跟踪红）及文件变化自动刷新。

### GitFileStatus（枚举）
文件 Git 状态：`ADDED`（暂存新增 A）、`MODIFIED`（修改 M）、`UNTRACKED`（未跟踪 ??）。

### GitStatusService（@Component）
基于 jgit（非 CLI）查询工作区状态。
- `Map<Path,GitFileStatus> queryStatusMap(Path root)`: 返回文件「绝对规范化路径 → 状态」映射；非 Git 仓库返回空 map。状态优先级 ADDED>MODIFIED>UNTRACKED。
- `Set<Path> collectChangedDirs(Map)`: 推导含改动的目录绝对路径集合。
- `Map<Integer,GitFileStatus> diffLineStatus(Path root, Path filePath)`: 计算单文件工作区相对 HEAD 的行级改动（键：工作区 0-based 行号 → 状态）。INSERT→ADDED，REPLACE→MODIFIED，DELETE→删除锚定到紧随其后的行（MODIFIED）；未跟踪新文件视为全部新增。供编辑器行号处按行着色。内部先统一两侧换行符为 LF，避免 autocrlf 下工作区 CRLF 与 HEAD LF 差异导致整文件误判为改动。
- `Set<Path> collectWatchDirs(Path root)`: 递归列出需监听的子目录（过滤忽略目录）。
- `boolean isIgnoredPath(Path)`: 判断是否应忽略的路径（监听事件过滤用）。
- 内置 `IGNORED_DIR_NAMES`：`.git`、`node_modules`、`target`、`build` 等。

### ProjectStatusStore（@Component）
当前展示项目的 Git 状态共享存储（bean）。
- 持有 `projectRoot`、`statusMap`、`changedDirs`。
- `update(root, map)`: 注入新状态并翻转 `refreshSignal`（BooleanProperty，风格同 `Store.refreshHistory`）触发 UI 刷新；根与状态映射均未变化时跳过翻转（避免进入目录树等场景的无意义刷新）。
- `GitFileStatus statusOf(Path)`、`boolean isDirChanged(Path)`: 供单元格/视图查询。

### ProjectFileWatcherService（@Component）
基于 `java.nio.file.WatchService` 递归监听项目根目录变化（`watch(root)`），去抖（600ms）后在后台重算 Git 状态并 `projectStatusStore.update(...)` 触发刷新。
- `watch(root)`/`stop()`: 开始/停止监听；仅在打开目录树时启用。
- 增量监听新建目录并补注册；跳过忽略目录。
- `@PreDestroy destroy()`: 应用关闭时释放资源。

## 设计模式
- Repository 模式：ProjectRegistry 管理项目列表的增删改查
- 服务模式：GitService 封装 Git 命令调用
- 持久化模式：JSON 文件持久化
- 懒加载模式：FileTreeService + LazyTreeItem 实现文件树按需加载，避免一次性加载大型项目所有文件

## 注意事项
1. ProjectRegistry 在构造时自动加载持久化数据
2. GitService 使用 ProcessBuilder 执行 git 命令，不依赖 JGit（GitStatusService 用 JGit 走内存计算，二者职责不重叠）
3. 项目路径验证：registerLocal 时检查路径是否为有效目录
4. 线程安全：ProjectRegistry 使用 CopyOnWriteArrayList
5. FileTreeService 的 `scanChildren` 为私有方法，仅通过 `LazyTreeItem` 的扫描函数注入后台执行；目录树刷新（`SideBarController.refreshProjectTree`）仅重扫展开的目录节点，折叠子树在再次展开时自动补扫
6. LazyTreeItem 位于 `cn.bitloom.node.project` 包（不在本包），但因与 FileTreeService 紧密耦合，在此一并说明
7. 目录树着色通过在 `SideBarController.showProjectTree` 设置 `FileTreeCell.setStatusStore(projectStatusStore)` 启用；Git 状态计算（全仓 `git status`）在 `statusExecutor` 后台线程执行，不在 FX 线程；文件视图（CoderEditorPanelController）订阅 `projectStatusStore.refreshSignal`，文件重读与行级 diff 同样在后台线程执行、FX 线程仅做 UI 应用
8. 目录树行 hover 灰底（`side-bar.css` 的 `file-tree__cell--hover` 样式类）由 `FileTreeCell` 按行索引驱动，不使用 CSS `:hover` 伪类——展开/折叠时 VirtualFlow 复用/重排 cell，伪类事件跨帧到达会瞬时丢失再恢复（表现为点击折叠箭头时灰影闪烁）；hover 行索引记录在 TreeView properties 的共享 `IntegerProperty` 上，接管该行的 cell 立即命中索引恢复高亮。目录树行同样不使用 `:pressed` 反馈（同因）。
