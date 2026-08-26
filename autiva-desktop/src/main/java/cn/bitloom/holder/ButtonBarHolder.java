package cn.bitloom.holder;

import javafx.event.EventHandler;
import javafx.scene.control.MenuButton;

import java.util.List;
import java.util.function.Consumer;

/**
 * ButtonBar 配置接口
 * 每个页面控制器实现此接口来定义自己的按钮配置
 *
 * @author bitloom
 */
public interface ButtonBarHolder {

    /**
     * 按钮对齐方式
     */
    enum Alignment {
        LEFT,   // 左对齐（放在 dynamicButtonContainer）
        RIGHT   // 右对齐（放在 rightButtonContainer）
    }

    /**
     * 获取按钮配置列表
     *
     * @return 按钮配置列表
     */
    List<ButtonConfig> getButtonConfigs();

    /**
     * 按钮配置类
     *
     * @param id            按钮 id
     * @param text          按钮文本（可有图标时配合使用）
     * @param styleClass    样式类
     * @param svgPath       图标路径（非空时显示图标）
     * @param alignment     对齐方式
     * @param actionHandler 单击动作
     * @param menuSetup     下拉菜单初始化器（非 null 时该按钮渲染为 MenuButton）
     */
    record ButtonConfig(
            String id,
            String text,
            String styleClass,
            String svgPath,
            Alignment alignment,
            EventHandler<javafx.event.ActionEvent> actionHandler,
            Consumer<MenuButton> menuSetup
    ) {
        // 兼容旧的构造方式（无图标，普通按钮，左对齐）
        public ButtonConfig(String id, String text, String styleClass, EventHandler<javafx.event.ActionEvent> actionHandler) {
            this(id, text, styleClass, null, Alignment.LEFT, actionHandler, null);
        }

        // 带图标，普通按钮，左对齐
        public ButtonConfig(String id, String text, String styleClass, String svgPath, EventHandler<javafx.event.ActionEvent> actionHandler) {
            this(id, text, styleClass, svgPath, Alignment.LEFT, actionHandler, null);
        }
    }
}