package cn.bitloom.harness.kernel;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件管理器 — Cordis 插件树的装载/卸载/依赖回滚（对标 dsh app-boot 的组合核心）。
 * <p>
 * 挂载：按调用顺序执行 plugin.apply(ctx)，期间产生的 Effect 逆序登记到该插件名下。
 * 卸载：逆序回滚该插件的全部 Effect；被后续插件依赖的服务缺失时，后续插件应显式
 * 卸载或依赖注入失败自然暴露（不做反向依赖追踪——保持内核最小）。
 * <p>
 * 对标 dsh Profile 的组装入口：{@link #mountAll(List)} 支持一次性挂载插件集
 * （code/work 模式即两个不同的插件集）。
 */
@Slf4j
public final class PluginManager {

    /** 插件名 → 挂载记录（保持挂载顺序） */
    private final Map<String, Registration> mounted = new LinkedHashMap<>();

    /** 宿主 ctx（非插件挂载期注册的落点，即插件树根） */
    private final HarnessContext rootContext;

    /** 所有插件 ctx 共享的服务/订阅存储 */
    private final SharedStore store;

    public PluginManager() {
        this.store = new SharedStore();
        this.rootContext = new HarnessContext(store, null, null);
    }

    /**
     * 挂载单个插件。同名插件重复挂载抛异常。
     */
    public synchronized HarnessContext mount(Plugin plugin) {
        if (plugin == null || plugin.name() == null || plugin.name().isBlank()) {
            throw new IllegalArgumentException("插件名不能为空");
        }
        if (mounted.containsKey(plugin.name())) {
            throw new IllegalArgumentException("插件已挂载: " + plugin.name());
        }
        List<Effect> effects = new ArrayList<>();
        HarnessContext ctx = new HarnessContext(store, plugin.name(), effects::add);
        try {
            plugin.apply(ctx);
        } catch (Exception e) {
            // 挂载失败回滚本插件已产生的 Effect，不留半挂载状态
            rollback(effects);
            throw new IllegalStateException("插件挂载失败: " + plugin.name(), e);
        }
        mounted.put(plugin.name(), new Registration(plugin, effects));
        log.info("[PluginManager] 挂载插件: {} (effects={})", plugin.name(), effects.size());
        return ctx;
    }

    /**
     * 按序挂载插件集（Profile 组装）。任何一个失败，全部逆序回滚。
     */
    public synchronized void mountAll(List<Plugin> plugins) {
        List<String> mountedNow = new ArrayList<>();
        try {
            for (Plugin plugin : plugins) {
                mount(plugin);
                mountedNow.add(plugin.name());
            }
        } catch (Exception e) {
            for (int i = mountedNow.size() - 1; i >= 0; i--) {
                unmount(mountedNow.get(i));
            }
            throw e;
        }
    }

    /**
     * 卸载插件：逆序回滚其全部 Effect。不存在返回 false。
     */
    public synchronized boolean unmount(String pluginName) {
        Registration registration = mounted.remove(pluginName);
        if (registration == null) {
            return false;
        }
        rollback(registration.effects());
        log.info("[PluginManager] 卸载插件: {}", pluginName);
        return true;
    }

    /** 卸载全部（逆序，进程关闭时用）。 */
    public synchronized void unmountAll() {
        List<String> names = new ArrayList<>(mounted.keySet());
        for (int i = names.size() - 1; i >= 0; i--) {
            unmount(names.get(i));
        }
    }

    /** 已挂载插件名（挂载顺序）。 */
    public synchronized List<String> mountedPluginNames() {
        return new ArrayList<>(mounted.keySet());
    }

    /** 插件是否已挂载。 */
    public synchronized boolean isMounted(String pluginName) {
        return mounted.containsKey(pluginName);
    }

    /** 宿主 ctx（根上下文：非插件代码可直接 provide/inject，如 Spring 启动装配）。 */
    public HarnessContext rootContext() {
        return rootContext;
    }

    private void rollback(List<Effect> effects) {
        for (int i = effects.size() - 1; i >= 0; i--) {
            effects.get(i).close();
        }
    }

    private record Registration(Plugin plugin, List<Effect> effects) { }
}
