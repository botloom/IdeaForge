package cn.bitloom.node;

import javafx.css.PseudoClass;
import javafx.scene.layout.Region;

/**
 * 折叠指示 chevron 箭头：与侧边栏目录树 {@code .tree-cell .arrow} 同款矢量形状。
 * <p>
 * 默认收起态为灰色朝右；展开态通过 {@code :expanded} 伪类驱动旋转 90° 指向下方
 * 并加深着色（机制与 TreeView 的 {@code :expanded} 一致）。
 * 尺寸、形状与配色由样式表 {@code .chevron-node} 规则定义。
 */
public class ChevronNode extends Region {

    private static final PseudoClass EXPANDED_PSEUDOCLASS = PseudoClass.getPseudoClass("expanded");

    public ChevronNode() {
        getStyleClass().add("chevron-node");
    }

    /** 设置展开态：true 旋转朝下并加深，false 收起朝右灰色。 */
    public void setExpanded(boolean expanded) {
        pseudoClassStateChanged(EXPANDED_PSEUDOCLASS, expanded);
    }
}
