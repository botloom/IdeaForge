package cn.bitloom.agentic.plugin;

/**
 * 动态插件作用域。
 * <ul>
 *   <li>{@link #SESSION}：会话级，会话关闭（SessionManager.remove）时自动卸载</li>
 *   <li>{@link #AGENT}：进程级，跨会话持续存在，直至显式卸载或应用重启</li>
 * </ul>
 */
public enum PluginScope {
    SESSION,
    AGENT
}
