package cn.bitloom.harness.kernel;

/**
 * 插件接口 — 一切能力单元的统一形态（Cordis Plugin 的 Java 对应物）。
 * <p>
 * 插件在 {@link #apply} 中通过 ctx.provide/on/effect 注册自己的贡献，
 * 全部注册自动挂到本插件名下，卸载时逆序回滚。
 * <p>
 * 依赖声明不设独立 inject 列表：apply 内直接 ctx.inject(key)，
 * 由 {@link PluginManager} 按声明顺序保证依赖先于使用方挂载；
 * 缺失依赖时 inject 返回 null / 抛异常，暴露组装错误。
 */
public interface Plugin {

    /** 插件名（全局唯一，卸载与冲突检测的 key）。 */
    String name();

    /** 挂载：向 ctx 注册服务/事件订阅/副作用。 */
    void apply(HarnessContext ctx);
}
