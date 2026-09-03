package cn.bitloom.node.message;

import cn.bitloom.node.ChevronNode;
import cn.bitloom.util.MarkdownFxRenderer;
import javafx.geometry.Pos;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.Setter;
import cn.bitloom.harness.llm.Role;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * 「参考内容」折叠卡片：会话首轮记忆召回（MemoryRecallEvent）在聊天流中的展示。
 * <p>
 * 折叠态仅显示标题，点击展开后列出本轮召回的记忆文件名（Markdown 链接样式，
 * 点击在应用右侧编辑器面板打开对应记忆文件）。交互与「思考过程」折叠容器一致。
 */
public class MemoryRecallCard extends MessageCard {

    private final List<String> files;
    private final File memoryDir;
    private final VBox section;
    private final VBox body;
    private final ChevronNode chevron;
    private boolean expanded = false;

    /** 内容变化回调（供 Controller 触发滚动 / Flowless 布局刷新）。 */
    @Setter
    private Consumer<String> onContentChanged;

    public MemoryRecallCard(List<String> files, File memoryDir) {
        this.files = List.copyOf(files);
        this.memoryDir = memoryDir;
        this.getStyleClass().add("memory-recall-card");
        this.getStyleClass().add("chat-message");

        Label title = new Label("参考内容");
        title.getStyleClass().add("fold-title");

        chevron = new ChevronNode();
        chevron.setExpanded(expanded);

        HBox header = new HBox(6);
        header.getStyleClass().add("fold-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.getChildren().addAll(title, chevron);
        header.setOnMouseClicked(e -> toggle());

        body = new VBox(4);
        body.getStyleClass().add("memory-recall-body");
        body.setMaxWidth(Double.MAX_VALUE);
        for (String file : this.files) {
            Hyperlink fileLink = new Hyperlink(file);
            fileLink.getStyleClass().addAll("md-link", "memory-recall-file");
            fileLink.setFocusTraversable(false);
            fileLink.setMaxWidth(Double.MAX_VALUE);
            fileLink.setOnAction(e -> openMemoryFile(file));
            body.getChildren().add(fileLink);
        }

        section = new VBox(4);
        section.getStyleClass().add("fold-section");
        // 初始折叠：body 不挂入 section（折叠即真正卸载节点，滚动时不参与 scene/layout/CSS）
        section.getChildren().add(header);
        section.setMaxWidth(Double.MAX_VALUE);

        this.setMaxWidth(Double.MAX_VALUE);
        this.getChildren().add(section);
    }

    private void toggle() {
        setExpanded(!expanded);
        notifyContentChanged();
    }

    /** 在应用右侧编辑器面板打开记忆文件（复用 Markdown 链接的注入 handler 打开链路）。 */
    private void openMemoryFile(String file) {
        try {
            File target = new File(memoryDir, file);
            if (target.isFile()) {
                MarkdownFxRenderer.openLink(target.toURI().toString());
            }
        } catch (Exception ignored) {
            // 无关联处理器等场景静默忽略
        }
    }

    private void setExpanded(boolean value) {
        expanded = value;
        if (value) {
            if (!section.getChildren().contains(body)) {
                section.getChildren().add(body);
            }
        } else {
            section.getChildren().remove(body);
        }
        chevron.setExpanded(value);
    }

    @Override
    public Role getMessageType() {
        // 助手侧内容，左对齐
        return Role.ASSISTANT;
    }

    @Override
    public String getContent() {
        return "[参考内容] " + String.join(", ", files);
    }

    private void notifyContentChanged() {
        if (onContentChanged != null) {
            onContentChanged.accept("");
        }
    }
}
