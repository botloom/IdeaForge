package cn.bitloom.node.tool;

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
 * 同一轮 AI 话语后连续调用的工具聚合到一张卡片。卡片默认折叠，标题显示组内工具的统计总结
 * （如「已读取 2 个文件 · 已修改 1 个文件 · 已执行 3 个命令」）；点击卡片展开查看逐条具体的
 * 操作卡片（每条为独立彩色主题卡，默认完整展现，不参与折叠）。
 * <p>
 * 分组壳弱化（白底浅边框），内部各工具条目为独立彩色主题卡（Read 蓝 / Write 绿 / Edit 橙 / Command 紫）。
 */
public class ToolCallCard extends VBox {

    private static final String FILE_SCHEME_PREFIX = "file:///";
    private static final String WINDOWS_DRIVE_PATTERN = "^[A-Za-z]:[\\\\\\\\/].*";

    @Setter
    private Consumer<String> onContentChanged;

    private final String baseDir;
    private boolean collapsed = true;

    private Label title;
    private VBox details;
    private final int[] counts = new int[Type.values().length];

    /** @param projectPath 项目根目录，用于将相对 filePath 解析为可点击的绝对路径链接 */
    public ToolCallCard(String projectPath) {
        this.baseDir = projectPath;
        buildShell();
    }

    /** 构建卡片外壳：标题栏（可点击折叠）+ 逐条详情容器。 */
    private void buildShell() {
        this.getStyleClass().add("tool-call-card");

        VBox content = new VBox(0);
        content.getStyleClass().add("tool-call-card__content");

        // ===== 标题栏（整卡点击切换折叠）：显示组内工具统计总结 =====
        HBox header = new HBox(8);
        header.getStyleClass().add("tool-call-card__header");
        header.setAlignment(Pos.CENTER_LEFT);

        title = new Label("");
        title.getStyleClass().add("tool-call-card__title");
        title.setWrapText(true);

        header.getChildren().add(title);

        // ===== 详情容器（展开态）：逐条工具调用，默认完整展现 =====
        details = new VBox(4);
        details.getStyleClass().add("tool-call-card__details");

        header.setOnMouseClicked(e -> toggleCollapse());

        content.getChildren().addAll(header, details);
        content.setMaxWidth(Double.MAX_VALUE);
        this.getChildren().add(content);

        // 默认折叠：仅显示标题总结，隐藏逐条详情
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
        details.getChildren().add(buildItem(type, arguments));
        title.setText(buildHeaderStat());
        notifyContentChanged();
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

    /** 设置卡片折叠状态：true=折叠（只显示标题总结）；false=展开（显示逐条详情）。 */
    private void setCollapsed(boolean collapsed) {
        if (this.collapsed != collapsed) {
            this.collapsed = collapsed;
            applyCollapseState();
        }
    }

    private void applyCollapseState() {
        details.setVisible(!collapsed);
        details.setManaged(!collapsed);
    }

    /** 点击卡片切换展开 / 折叠。 */
    private void toggleCollapse() {
        setCollapsed(!collapsed);
    }

    /** 渲染组内单个工具调用（展开态）：工具名 + 徽标 + 提示 + 主体；独立彩色主题卡。 */
    private VBox buildItem(Type type, String arguments) {
        VBox item = new VBox(6);
        item.getStyleClass().add("tool-call-card__item");
        item.getStyleClass().add("tool-call-card__item--" + type.variantSuffix);

        // 第一行：工具名（弱化）+ 右侧提示与徽标
        HBox row = new HBox(8);
        row.getStyleClass().add("tool-call-card__item-header");
        row.setAlignment(Pos.CENTER_LEFT);

        Label name = new Label(type.title);
        name.getStyleClass().add("tool-call-card__tool-name");

        Region spacer = new Region();
        row.getChildren().addAll(name, spacer);
        HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);

        String meta = buildMeta(type, arguments);
        if (meta != null) {
            Label metaLabel = new Label(meta);
            metaLabel.getStyleClass().add("tool-call-card__meta");
            row.getChildren().add(metaLabel);
        }
        appendBadge(row, type, arguments);

        item.getChildren().add(row);

        // 主体：文件链接 / 命令代码块
        VBox body = buildBody(type, arguments);
        if (body != null) {
            item.getChildren().add(body);
        }
        return item;
    }

    /** 在标题行右侧追加类型相关的徽标。 */
    private void appendBadge(HBox row, Type type, String arguments) {
        String badge = null;
        switch (type) {
            case WRITE -> badge = "新建";
            case EDIT -> badge = "true".equals(JsonUtils.extractString(arguments, "replace_all"))
                    ? "全局替换" : "单处替换";
            case COMMAND -> badge = "true".equals(JsonUtils.extractString(arguments, "run_in_background"))
                    ? "后台" : "前台";
            default -> { }
        }
        if (badge != null) {
            Label b = new Label(badge);
            b.getStyleClass().add("tool-call-card__badge");
            row.getChildren().add(b);
        }
    }

    /** 构建弱化的副信息（每类型侧重不同）。 */
    private String buildMeta(Type type, String arguments) {
        switch (type) {
            case READ -> {
                String offset = JsonUtils.extractString(arguments, "offset");
                String limit = JsonUtils.extractString(arguments, "limit");
                StringBuilder sb = new StringBuilder();
                if (offset != null && !offset.isBlank()) {
                    sb.append("从第 ").append(offset).append(" 行起");
                } else {
                    sb.append("从头");
                }
                if (limit != null && !limit.isBlank()) {
                    sb.append("，读取 ").append(limit).append(" 行");
                } else {
                    sb.append("读取");
                }
                return sb.toString();
            }
            case COMMAND -> {
                String timeout = JsonUtils.extractString(arguments, "timeout");
                if (timeout == null || timeout.isBlank()) {
                    return null;
                }
                long ms;
                try {
                    ms = Long.parseLong(timeout);
                } catch (NumberFormatException e) {
                    return null;
                }
                return "超时 " + (ms >= 1000 ? (ms / 1000) + "s" : ms + "ms");
            }
            default -> { return null; }
        }
    }

    /** 主体：文件类用可点击文件名链接；命令用代码块。 */
    private VBox buildBody(Type type, String arguments) {
        switch (type) {
            case READ, WRITE, EDIT -> {
                String filePath = JsonUtils.extractString(arguments, "filePath", "file_path");
                if (filePath == null || filePath.isBlank()) {
                    return null;
                }
                return renderMarkdown(linkMarkdown(filePath));
            }
            case COMMAND -> {
                String command = JsonUtils.extractString(arguments, "command");
                if (command == null || command.isBlank()) {
                    return null;
                }
                return renderMarkdown("```bash\n" + command + "\n```");
            }
            default -> { return null; }
        }
    }

    private String linkMarkdown(String filePath) {
        String display = new File(filePath).getName();
        String abs = normalize(filePath, baseDir);
        return "- [" + display + "](" + FILE_SCHEME_PREFIX + abs.replace("\\", "/") + ")";
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

    /** 构建标题的统计总结：只列出非空的类型段，如「已读取 2 个文件 · 已修改 1 个文件 · 已执行 3 个命令」。 */
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
        READ("读取文件", "read", "已读取 %d 个文件"),
        WRITE("写入文件", "write", "已写入 %d 个文件"),
        EDIT("修改文件", "edit", "已修改 %d 个文件"),
        COMMAND("执行命令", "command", "已执行 %d 个命令");

        final String title;
        final String variantSuffix;
        final String statFmt;

        Type(String title, String variantSuffix, String statFmt) {
            this.title = title;
            this.variantSuffix = variantSuffix;
            this.statFmt = statFmt;
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
