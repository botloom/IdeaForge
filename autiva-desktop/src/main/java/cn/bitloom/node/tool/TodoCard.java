package cn.bitloom.node.tool;

import cn.bitloom.node.svg.SvgImageView;
import cn.bitloom.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.util.ArrayList;
import java.util.List;

public class TodoCard extends VBox {

    private static final double RING_RADIUS = 12;
    private static final double RING_STROKE = 3;
    private static final double RING_CIRCUMFERENCE = 2 * Math.PI * RING_RADIUS;

    /** 关闭回调（非 null 时头部渲染表头栏，供编辑器面板等宿主视图使用） */
    private final Runnable onClose;

    public TodoCard(String todosJson) {
        this(todosJson, null);
    }

    /**
     * @param hostCloseAction 面板视图宿主注入的关闭回调；构造期传入以保证首帧即渲染表头栏，
     *                        避免事后 setOnClose 导致初次显示缺表头
     */
    public TodoCard(String todosJson, Runnable hostCloseAction) {
        this.onClose = hostCloseAction;
        getStyleClass().add("chat-message");
        getStyleClass().add("chat-message--tool");
        getStyleClass().add("chat-message--todo");
        // 卡片宽度填满容器，不基于内容自然宽度撑大
        setMaxWidth(Double.MAX_VALUE);
        setPrefWidth(Region.USE_COMPUTED_SIZE);
        rebuild(todosJson);
    }

    /**
     * 原地更新卡片内容（不新建卡片）
     */
    public void update(String todosJson) {
        getChildren().clear();
        rebuild(todosJson);
    }

    private void rebuild(String todosJson) {
        List<JsonNode> todoItems = parseTodos(todosJson);
        int completedCount = 0;
        int totalCount = todoItems.size();
        for (JsonNode item : todoItems) {
            if ("completed".equals(getString(item, "status"))) {
                completedCount++;
            }
        }

        // 表头栏：只放右侧关闭按钮（面板视图注入 onClose 时显示），其余信息放入内容区
        if (onClose != null) {
            HBox header = new HBox();
            header.getStyleClass().add("chat-message__todo-header");
            header.setAlignment(Pos.CENTER_RIGHT);
            header.setMaxWidth(Double.MAX_VALUE);
            header.setPrefWidth(Region.USE_COMPUTED_SIZE);

            Button closeButton = new Button();
            closeButton.getStyleClass().add("editor-panel__sub-tab-add");
            SvgImageView closeIcon = new SvgImageView();
            closeIcon.setFitWidth(14);
            closeIcon.setFitHeight(14);
            closeIcon.setSvgPath("/cn/bitloom/images/close.svg");
            closeButton.setGraphic(closeIcon);
            closeButton.setOnAction(e -> onClose.run());
            header.getChildren().add(closeButton);
            getChildren().add(header);
        }

        // 内容区
        VBox body = new VBox(4);
        body.getStyleClass().add("chat-message__todo-body");
        body.setMaxWidth(Double.MAX_VALUE);
        body.setPrefWidth(Region.USE_COMPUTED_SIZE);

        // 信息行：进度环 + 工具名 + 计数摘要
        HBox infoRow = new HBox(10);
        infoRow.setAlignment(Pos.CENTER_LEFT);
        infoRow.setMaxWidth(Double.MAX_VALUE);

        StackPane progressRing = createProgressRing(completedCount, totalCount);
        infoRow.getChildren().add(progressRing);

        VBox titleBox = new VBox(1);
        titleBox.setMaxWidth(Double.MAX_VALUE);
        titleBox.setPrefWidth(Region.USE_COMPUTED_SIZE);
        Label nameLabel = new Label("TodoWrite");
        nameLabel.getStyleClass().add("chat-message__tool-name");
        nameLabel.setStyle("-fx-text-fill: #b45309;");
        Label summaryText = new Label(completedCount + " / " + totalCount + " 已完成");
        summaryText.getStyleClass().add("chat-message__todo-summary-text");
        titleBox.getChildren().addAll(nameLabel, summaryText);
        infoRow.getChildren().add(titleBox);
        body.getChildren().add(infoRow);
        // 信息行与卡片清单之间的分隔间距
        VBox.setMargin(infoRow, new Insets(0, 0, 8, 0));

        // 待办清单
        for (JsonNode item : todoItems) {
            String content = getString(item, "content");
            String status = getString(item, "status");
            String activeForm = getString(item, "activeForm");

            HBox itemRow = new HBox(10);
            itemRow.getStyleClass().add("chat-message__todo-item");
            // 状态变体类：驱动小卡片的差异化配色（进行中浅蓝底/已完成淡化）
            itemRow.getStyleClass().add("chat-message__todo-item--" + status);
            itemRow.setAlignment(Pos.CENTER_LEFT);
            // 行宽受限于卡片/容器，避免长内容将 todo 卡片横向撑开
            itemRow.setMaxWidth(Double.MAX_VALUE);
            itemRow.setPrefWidth(Region.USE_COMPUTED_SIZE);

            Circle statusDot = new Circle(4);
            statusDot.getStyleClass().add("chat-message__todo-status");
            statusDot.getStyleClass().add("chat-message__todo-status--" + status);
            itemRow.getChildren().add(statusDot);

            Label contentLabel = new Label(content);
            contentLabel.setWrapText(true);
            // 允许收缩到 0，避免长单词将行宽撑出容器（配合 itemRow setMaxWidth 不横向溢出）
            contentLabel.setMinWidth(0);
            contentLabel.setMaxWidth(Double.MAX_VALUE);
            contentLabel.getStyleClass().add("chat-message__todo-text");
            if ("completed".equals(status)) {
                contentLabel.getStyleClass().add("chat-message__todo-text--completed");
            }
            HBox.setHgrow(contentLabel, Priority.ALWAYS);
            itemRow.getChildren().add(contentLabel);

            if (activeForm != null && !"completed".equals(status)) {
                Label activeFormLabel = new Label(activeForm);
                activeFormLabel.getStyleClass().add("chat-message__todo-active-form");
                // 锁定为内容宽度，不参与行内收缩，避免被压成省略号
                activeFormLabel.setMinWidth(Region.USE_PREF_SIZE);
                activeFormLabel.setMaxWidth(Region.USE_PREF_SIZE);
                itemRow.getChildren().add(activeFormLabel);
            }

            Label statusLabel = new Label(getStatusText(status));
            statusLabel.getStyleClass().add("chat-message__todo-status-label");
            statusLabel.getStyleClass().add("chat-message__todo-status-label--" + status);
            // 锁定为内容宽度，不参与行内收缩，状态文字始终完整显示
            statusLabel.setMinWidth(Region.USE_PREF_SIZE);
            statusLabel.setMaxWidth(Region.USE_PREF_SIZE);
            itemRow.getChildren().add(statusLabel);

            body.getChildren().add(itemRow);
        }
        getChildren().add(body);
    }

    /**
     * 创建环形进度指示器：背景灰圆环 + 前景绿圆环（按完成比例显示弧长）
     */
    private StackPane createProgressRing(int completed, int total) {
        StackPane ring = new StackPane();
        ring.getStyleClass().add("chat-message__todo-progress-ring");

        Circle bg = new Circle(RING_RADIUS);
        bg.getStyleClass().add("chat-message__todo-progress-ring-bg");
        bg.setStrokeWidth(RING_STROKE);
        bg.setFill(null);

        Circle fg = new Circle(RING_RADIUS);
        fg.getStyleClass().add("chat-message__todo-progress-ring-fg");
        fg.setStrokeWidth(RING_STROKE);
        fg.setFill(null);
        fg.setRotationAxis(javafx.scene.transform.Rotate.Z_AXIS);
        fg.setRotate(-90);
        if (total > 0) {
            double ratio = (double) completed / total;
            fg.getStrokeDashArray().addAll(ratio * RING_CIRCUMFERENCE, RING_CIRCUMFERENCE);
        } else {
            fg.getStrokeDashArray().addAll(0.0, RING_CIRCUMFERENCE);
        }

        ring.getChildren().addAll(bg, fg);
        return ring;
    }

    private String getStatusText(String status) {
        return switch (status) {
            case "pending" -> "待处理";
            case "in_progress" -> "进行中";
            case "completed" -> "已完成";
            default -> status;
        };
    }

    private List<JsonNode> parseTodos(String todosJson) {
        try {
            JsonNode parsed = JsonUtils.parse(todosJson);
            if (parsed != null && parsed.has("todos")) {
                JsonNode arr = parsed.get("todos");
                if (arr != null && !arr.isNull() && arr.isArray()) {
                    List<JsonNode> result = new ArrayList<>();
                    for (JsonNode item : arr) {
                        result.add(item);
                    }
                    return result;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return List.of();
    }

    private String getString(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value != null && !value.isNull() ? value.asText() : null;
    }
}
