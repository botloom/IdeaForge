package cn.bitloom.agentic.plugin;

import java.util.List;

/**
 * 动态插件 — 内存态的智能体能力扩展单元（对应 Cordis 插件的声明式子集）。
 * <p>
 * 仅存在于进程内存，重启即消失；通过 {@link PluginRegistry} 挂载/卸载，
 * 生命周期按 {@link PluginScope} 管理，卸载时干净回收。
 *
 * @param name        插件名（唯一）
 * @param description 插件描述
 * @param tools       插件提供的工具列表
 */
public record DynamicPlugin(String name, String description, List<ToolSpec> tools) {
}
