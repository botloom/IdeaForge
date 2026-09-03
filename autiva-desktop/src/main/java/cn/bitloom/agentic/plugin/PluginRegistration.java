package cn.bitloom.agentic.plugin;

import lombok.Getter;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件挂载注册项 — 持有插件元数据与可逆清理路径（对应 Cordis 的 Effect 注册表）。
 * <p>
 * unmount 时执行全部 disposer，保证挂载产生的副作用被干净回收。
 */
@Getter
public class PluginRegistration {

    private final DynamicPlugin plugin;
    private final PluginScope scope;
    /** SESSION 作用域时为挂载者的 sessionId；AGENT 作用域为 null */
    private final String ownerId;
    private final List<Runnable> disposers = new ArrayList<>();
    private final List<DeclarativeToolCallback> callbacks;

    PluginRegistration(DynamicPlugin plugin, PluginScope scope, String ownerId,
                       List<DeclarativeToolCallback> callbacks) {
        this.plugin = plugin;
        this.scope = scope;
        this.ownerId = ownerId;
        this.callbacks = List.copyOf(callbacks);
    }

    /** 注册一个卸载时执行的清理动作（可逆副作用） */
    void addDisposer(Runnable disposer) {
        if (disposer != null) {
            disposers.add(disposer);
        }
    }

    boolean isVisibleTo(String sessionId) {
        return scope == PluginScope.AGENT
                || (scope == PluginScope.SESSION && ownerId != null && ownerId.equals(sessionId));
    }
}
