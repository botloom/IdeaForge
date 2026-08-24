package cn.bitloom.node;

import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 斜杠命令自动补全弹窗：输入框以 "/" 开头时显示候选命令，
 * 上下箭头选择、回车确认（确认即回填选中命令到输入框，由发送流程再执行）。
 * <p>
 * 候选项按“别名 | 说明”展示，输入以别名前缀过滤。
 */
public class SlashCommandPopup {

    /** 一个斜杠命令候选：回填别名 + 说明文本。 */
    public record CommandOption(String fill, String hint) {
    }

    private final Popup popup = new Popup();
    private final VBox listBox = new VBox(2);
    private final List<CommandOption> allOptions = new ArrayList<>();
    /** 当前过滤后可见的候选（索引对齐 listBox children）。 */
    private final List<CommandOption> visibleOptions = new ArrayList<>();
    private int selectedIndex = -1;

    private final Consumer<String> confirmHandler;

    public SlashCommandPopup(Consumer<String> confirmHandler) {
        this.confirmHandler = confirmHandler;
        setupPopup();
    }

    private void setupPopup() {
        popup.setAutoHide(true);
        popup.setAutoFix(true);
        popup.setHideOnEscape(true);

        listBox.setPadding(new Insets(6, 4, 6, 4));
        listBox.setStyle("""
                -fx-background-color: rgba(255, 255, 255, 0.97);
                -fx-background-radius: 10px;
                -fx-border-color: rgba(0, 0, 0, 0.12);
                -fx-border-width: 1px;
                -fx-border-radius: 10px;
                -fx-effect: dropshadow(gaussian, rgba(0, 0, 0, 0.14), 18, 0.2, 0, 6);
                """);
        popup.getContent().add(listBox);
    }

    /** 注册候选命令列表。 */
    public void setCommands(List<CommandOption> options) {
        allOptions.clear();
        allOptions.addAll(options);
    }

    /** 以输入前缀过滤并展示候选，定位到 anchor（输入框）正上方。无匹配时隐藏。 */
    public void show(Region anchor, String prefix) {
        List<CommandOption> filtered = new ArrayList<>();
        String query = prefix.trim();
        for (CommandOption opt : allOptions) {
            if (query.isEmpty() || opt.fill().toLowerCase().startsWith(query.toLowerCase())) {
                filtered.add(opt);
            }
        }
        if (filtered.isEmpty()) {
            hide();
            return;
        }
        visibleOptions.clear();
        visibleOptions.addAll(filtered);
        rebuildList();
        selectedIndex = Math.min(selectedIndex, visibleOptions.size() - 1);
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }
        applySelection();
        positionAbove(anchor);
        if (!popup.isShowing()) {
            Window window = anchor.getScene() != null ? anchor.getScene().getWindow() : null;
            if (window != null) {
                popup.show(window);
            }
        }
    }

    /** 定位弹窗到 anchor 正上方（屏幕坐标），随输入框变化刷新。 */
    private void positionAbove(Region anchor) {
        javafx.geometry.Bounds screen = anchor.localToScreen(anchor.getBoundsInLocal());
        if (screen == null) {
            return;
        }
        double popupHeight = listBox.prefHeight(-1);
        double x = screen.getMinX();
        double y = screen.getMinY() - popupHeight - 6;
        popup.setAnchorX(x);
        popup.setAnchorY(y);
    }

    public void hide() {
        popup.hide();
    }

    public boolean isShowing() {
        return popup.isShowing();
    }

    /** 上移选中。 */
    public void moveUp() {
        if (visibleOptions.isEmpty()) {
            return;
        }
        selectedIndex = (selectedIndex - 1 + visibleOptions.size()) % visibleOptions.size();
        applySelection();
    }

    /** 下移选中。 */
    public void moveDown() {
        if (visibleOptions.isEmpty()) {
            return;
        }
        selectedIndex = (selectedIndex + 1) % visibleOptions.size();
        applySelection();
    }

    /** 确认当前选中项：回填命令并隐藏。 */
    public void confirm() {
        if (selectedIndex < 0 || selectedIndex >= visibleOptions.size()) {
            return;
        }
        CommandOption opt = visibleOptions.get(selectedIndex);
        popup.hide();
        if (confirmHandler != null) {
            confirmHandler.accept(opt.fill());
        }
    }

    private void rebuildList() {
        listBox.getChildren().clear();
        for (CommandOption opt : visibleOptions) {
            Label label = new Label(opt.fill() + "   " + opt.hint());
            label.setStyle("-fx-font-family: \"SF Pro Text\", \"Segoe UI\", system, sans-serif;"
                    + "-fx-font-size: 13px; -fx-text-fill: #1d1d1f;"
                    + "-fx-padding: 5 10 5 10; -fx-background-radius: 6px;");
            label.setPrefWidth(220);
            label.setMaxWidth(Region.USE_PREF_SIZE);
            final int idx = listBox.getChildren().size();
            label.setOnMouseEntered(e -> {
                selectedIndex = idx;
                applySelection();
            });
            label.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) {
                    selectedIndex = idx;
                    confirm();
                }
            });
            listBox.getChildren().add(label);
        }
    }

    private void applySelection() {
        for (int i = 0; i < listBox.getChildren().size(); i++) {
            Label label = (Label) listBox.getChildren().get(i);
            boolean selected = i == selectedIndex;
            if (selected) {
                label.setStyle("-fx-font-family: \"SF Pro Text\", \"Segoe UI\", system, sans-serif;"
                        + "-fx-font-size: 13px; -fx-text-fill: #ffffff;"
                        + "-fx-padding: 5 10 5 10; -fx-background-color: #0071e3;"
                        + "-fx-background-radius: 6px;");
            } else {
                label.setStyle("-fx-font-family: \"SF Pro Text\", \"Segoe UI\", system, sans-serif;"
                        + "-fx-font-size: 13px; -fx-text-fill: #1d1d1f;"
                        + "-fx-padding: 5 10 5 10; -fx-background-radius: 6px;");
            }
        }
    }
}
