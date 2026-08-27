package cn.bitloom.node.tool;

import cn.bitloom.node.ChevronNode;
import cn.bitloom.node.svg.SvgImageView;
import cn.bitloom.util.JsonUtils;
import cn.bitloom.util.MarkdownFxRenderer;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import lombok.Setter;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 工具调用分组卡片：承载一组连续的工具调用（Read / Write / Edit / Command）。
 * <p>
 * 卡片默认折叠，标题行显示组内工具的统计总结（如「已读取 2 个文件，已修改 1 个文件，
 * 已执行 3 个命令」）；点击展开后为按类型分组的树形明细：每个类型一行
 * 「icon + 已读取 N 个文件」分组头，其下带左侧竖线引导线的明细列表
 * （文件为可点击链接，命令为等宽文本）。
 * <p>
 * 工具流式追加时整体幂等重建明细区。
 */
public class ToolCallCard extends VBox {

    private static final String FILE_SCHEME_PREFIX = "file:///";
    private static final String WINDOWS_DRIVE_PATTERN = "^[A-Za-z]:[\\\\/].*";

    @Setter
    private Consumer<String> onContentChanged;

    private final String baseDir;
    private boolean collapsed = true;

    private Label title;
    private VBox details;
    private ChevronNode chevron;
    private final int[] counts = new int[Type.values().length];
    private final List<ToolEntry> entries = new ArrayList<>();

    private record ToolEntry(Type type, String arguments) {
    }

    /** @param projectPath 项目根目录，用于将相对 filePath 解析为可点击的绝对路径链接 */
    public ToolCallCard(String projectPath) {
        this.baseDir = projectPath;
        buildShell();
    }

    /** 构建卡片外壳：标题栏（可点击折叠）+ 树形明细容器。 */
    private void buildShell() {
        this.getStyleClass().add("tool-call-card");

        VBox content = new VBox(0);
        content.getStyleClass().add("tool-call-card__content");

        // ===== 标题栏（整卡点击切换折叠）：显示组内工具统计总结 =====
        HBox header = new HBox(4);
        header.getStyleClass().add("tool-call-card__header");
        header.setAlignment(Pos.CENTER_LEFT);

        title = new Label("");
        title.getStyleClass().add("tool-call-card__title");
        title.setWrapText(true);

        chevron = new ChevronNode();

        header.getChildren().addAll(title, chevron);

        // ===== 明细容器（展开态）：按类型分组的树形明细 =====
        details = new VBox(2);
        details.getStyleClass().add("tool-call-card__details");

        header.setOnMouseClicked(e -> toggleCollapse());

        content.getChildren().addAll(header, details);
        content.setMaxWidth(Double.MAX_VALUE);
        this.getChildren().add(content);

        // 默认折叠：仅显示标题总结
        applyCollapseState();
    }

    /**
     * 追加一次工具调用到当前卡片（支持多次调用聚合为一组）。
     *
     * @param toolName  工具名称（Read/Write/Edit/Command）
     * @param arguments 工具入参 JSON 字符串
     */
    public void addToolCall(String toolName, String arguments) {
        Type type = typeOf(toolName);
        counts[type.ordinal()]++;
        entries.add(new ToolEntry(type, arguments));
        rebuildDetails();
        title.setText(buildHeaderStat());
        notifyContentChanged();
    }

    /** 幂等重建明细区：按类型固定顺序分组，每组为「分组头 + 竖线引导的明细列表」。 */
    private void rebuildDetails() {
        details.getChildren().clear();
        for (Type type : Type.values()) {
            if (counts[type.ordinal()] == 0) {
                continue;
            }
            VBox group = new VBox(2);
            group.getStyleClass().add("tool-call-card__group");
            group.getChildren().add(buildGroupHeader(type, counts[type.ordinal()]));

            VBox groupEntries = new VBox(2);
            groupEntries.getStyleClass().add("tool-call-card__entries");
            for (ToolEntry entry : entries) {
                if (entry.type() != type) {
                    continue;
                }
                Node node = buildEntryNode(type, entry.arguments());
                if (node != null) {
                    groupEntries.getChildren().add(node);
                }
            }
            group.getChildren().add(groupEntries);
            details.getChildren().add(group);
        }
    }

    /** 分组头：类型 icon + 「已读取 N 个文件」。 */
    private HBox buildGroupHeader(Type type, int count) {
        HBox header = new HBox(6);
        header.getStyleClass().add("tool-call-card__group-header");
        header.setAlignment(Pos.CENTER_LEFT);

        SvgImageView icon = new SvgImageView();
        icon.setFitWidth(12);
        icon.setFitHeight(12);
        icon.setSvgPath(type.iconPath);
        icon.setStrokeColor(type.color);

        Label label = new Label(type.stat(count));
        label.getStyleClass().add("tool-call-card__group-title");

        header.getChildren().addAll(icon, label);
        return header;
    }

    /** 单条明细：文件类为可点击文件名链接；命令为等宽文本。 */
    private Node buildEntryNode(Type type, String arguments) {
        switch (type) {
            case READ, WRITE, EDIT -> {
                String filePath = JsonUtils.extractString(arguments, "filePath", "file_path");
                if (filePath == null || filePath.isBlank()) {
                    return null;
                }
                VBox entry = renderMarkdown(linkMarkdown(filePath));
                entry.getStyleClass().add("tool-call-card__entry");
                return entry;
            }
            case COMMAND -> {
                String command = JsonUtils.extractString(arguments, "command");
                if (command == null || command.isBlank()) {
                    return null;
                }
                Label label = new Label(command);
                label.setWrapText(true);
                label.getStyleClass().add("tool-call-card__command");
                return label;
            }
            default -> { return null; }
        }
    }

    /**
     * 标记卡片执行中（供 ViewModel 在工具 CREATED 时调用）。
     * 卡片默认折叠，执行中不自动展开。
     */
    public void markRunning() {
        // 默认折叠：执行中不展开详情
    }

    /**
     * 组执行结束：保持折叠（供 ViewModel 在组内工具全部 COMPLETED/FAILED 后调用）。
     */
    public void collapseNow() {
        setCollapsed(true);
    }

    /** 设置卡片折叠状态：true=折叠（只显示标题总结）；false=展开（显示树形明细）。 */
    private void setCollapsed(boolean collapsed) {
        if (this.collapsed != collapsed) {
            this.collapsed = collapsed;
            applyCollapseState();
        }
    }

    private void applyCollapseState() {
        details.setVisible(!collapsed);
        details.setManaged(!collapsed);
        if (chevron != null) {
            chevron.setExpanded(!collapsed);
        }
    }

    /** 点击卡片切换展开 / 折叠。 */
    private void toggleCollapse() {
        setCollapsed(!collapsed);
    }

    private String linkMarkdown(String filePath) {
        String display = new File(filePath).getName();
        String abs = normalize(filePath, baseDir);
        // 纯链接（非列表项），避免 Markdown 列表渲染带来的圆点缩进
        return "[" + display + "](" + FILE_SCHEME_PREFIX + abs.replace("\\", "/") + ")";
    }

    private VBox renderMarkdown(String markdown) {
        VBox box = new VBox(0);
        box.getStyleClass().add("tool-call-card__body");
        try {
            VBox rendered = MarkdownFxRenderer.render(markdown);
            for (Node child : new ArrayList<>(rendered.getChildren())) {
                if (child instanceof Region region) {
                    region.setMaxWidth(Double.MAX_VALUE);
                }
                box.getChildren().add(child);
            }
        } catch (Exception e) {
            Label fallback = new Label(markdown);
            fallback.setWrapText(true);
            fallback.getStyleClass().add("tool-call-card__fallback");
            box.getChildren().add(fallback);
        }
        return box;
    }

    /** 构建标题的统计总结：只列出非空的类型段，如「已读取 2 个文件，已修改 1 个文件，已执行 3 个命令」。 */
    private String buildHeaderStat() {
        List<String> parts = new ArrayList<>();
        for (Type type : Type.values()) {
            if (counts[type.ordinal()] > 0) {
                parts.add(type.stat(counts[type.ordinal()]));
            }
        }
        return String.join("，", parts);
    }

    private void notifyContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.accept("tool-call-change");
        }
    }

    /** 将相对路径基于项目根目录解析为绝对路径。 */
    private static String normalize(String filePath, String baseDir) {
        if (filePath.matches(WINDOWS_DRIVE_PATTERN) || filePath.startsWith("/")) {
            return filePath;
        }
        if (baseDir != null && !baseDir.isBlank()) {
            Path base = Paths.get(baseDir);
            Path resolved = base.resolve(filePath).normalize();
            return resolved.toString();
        }
        return filePath;
    }

    private enum Type {
        READ("已读取 %d 个文件", "/cn/bitloom/images/file-text.svg", "#0b63d8"),
        WRITE("已写入 %d 个文件", "/cn/bitloom/images/file-new.svg", "#1f9a47"),
        EDIT("已修改 %d 个文件", "/cn/bitloom/images/diff.svg", "#d97e04"),
        COMMAND("已执行 %d 个命令", "/cn/bitloom/images/code.svg", "#5b57cf");

        final String statFmt;
        final String iconPath;
        final String color;

        Type(String statFmt, String iconPath, String color) {
            this.statFmt = statFmt;
            this.iconPath = iconPath;
            this.color = color;
        }

        String stat(int n) {
            return String.format(statFmt, n);
        }
    }

    private static Type typeOf(String toolName) {
        if (toolName == null) {
            return Type.COMMAND;
        }
        return switch (toolName) {
            case "Read" -> Type.READ;
            case "Write" -> Type.WRITE;
            case "Edit" -> Type.EDIT;
            default -> Type.COMMAND;
        };
    }
}
