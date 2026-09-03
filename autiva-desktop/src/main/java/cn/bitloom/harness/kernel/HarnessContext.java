package cn.bitloom.harness.kernel;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Harness 上下文 — Cordis ctx 的 Java 对应物：服务容器 + 类型化事件总线 + 可逆注册。
 * <p>
 * 插件通过 {@link #provide} 把服务写入 ctx，通过 {@link #inject} 声明并获取依赖，
 * 通过 {@link #on} 订阅类型化事件，通过 {@link #effect} 登记任意需要撤销的副作用。
 * <p>
 * <strong>共享语义：</strong>同一 {@link PluginManager} 下所有插件的 ctx 共享同一份
 * 服务表与订阅表（由 {@link SharedStore} 承载），因此插件 A provide 的服务可被插件 B
 * inject。Effect 仍按插件名分组记录，插件卸载时由 PluginManager 逆序回滚。
 * <p>
 * 线程模型：服务表与订阅表并发安全；provide 同 key 二次注册抛异常（替换式注册走
 * {@link #replace}）。
 */
public final class HarnessContext {

    private final SharedStore store;

    /** 当前挂载插件名（由 PluginManager 维护；null 表示根上下文/非挂载期注册） */
    private final String pluginName;

    /** 注册到当前插件名下的 Effect 列表（由 PluginManager 持有回滚，此处仅转发登记） */
    private final EffectCollector collector;

    HarnessContext(SharedStore store, String pluginName, EffectCollector collector) {
        this.store = store;
        this.pluginName = pluginName;
        this.collector = collector;
    }

    /** Effect 登记回调（PluginManager 注入）。 */
    interface EffectCollector {
        void collect(Effect effect);
    }

    /**
     * 提供服务（Cordis ctx.provide）。注册可逆：插件卸载时移除该服务。
     *
     * @param key      服务键
     * @param instance 服务实例
     * @return 本次注册的 Effect
     */
    public <T> Effect provide(ServiceKey<T> key, T instance) {
        if (instance == null) {
            throw new IllegalArgumentException("provide 不接受 null 服务: " + key);
        }
        Object prev = store.services.putIfAbsent(key.name(), instance);
        if (prev != null && prev != instance) {
            throw new IllegalStateException("服务键已被注册: " + key + "（先卸载或用 replace）");
        }
        Effect effect = new Effect(pluginName, "provide:" + key.name(),
                () -> store.services.remove(key.name(), instance));
        if (collector != null) {
            collector.collect(effect);
        }
        return effect;
    }

    /**
     * 替换式注册（热替换）：移除旧服务并写入新服务。
     */
    public <T> Effect replace(ServiceKey<T> key, T instance) {
        store.services.remove(key.name());
        return provide(key, instance);
    }

    /**
     * 注入服务（Cordis inject）。未注册返回 null；类型不匹配抛异常（快速暴露配置错误）。
     */
    @SuppressWarnings("unchecked")
    public <T> T inject(ServiceKey<T> key) {
        Object value = store.services.get(key.name());
        if (value == null) {
            return null;
        }
        if (!key.type().isInstance(value)) {
            throw new IllegalStateException("服务类型不匹配: key=" + key
                    + ", actual=" + value.getClass().getName());
        }
        return (T) value;
    }

    /** 服务是否已注册。 */
    public boolean has(ServiceKey<?> key) {
        return store.services.containsKey(key.name());
    }

    /**
     * 订阅类型化事件（Cordis ctx.on）。订阅可逆：插件卸载时自动退订。
     */
    public <E> Effect on(EventKey<E> key, Consumer<E> handler) {
        Consumer<Object> subscriber = event -> handler.accept(key.type().cast(event));
        store.subscribers.computeIfAbsent(key.name(), k -> new CopyOnWriteArrayList<>()).add(subscriber);
        Effect effect = new Effect(pluginName, "on:" + key.name(),
                () -> {
                    List<Consumer<Object>> list = store.subscribers.get(key.name());
                    if (list != null) {
                        list.remove(subscriber);
                    }
                });
        if (collector != null) {
            collector.collect(effect);
        }
        return effect;
    }

    /**
     * 发布类型化事件，同步通知全部订阅者。单个订阅者异常不影响其它订阅者。
     */
    public <E> void emit(EventKey<E> key, E event) {
        List<Consumer<Object>> list = store.subscribers.get(key.name());
        if (list == null) {
            return;
        }
        for (Consumer<Object> s : list) {
            try {
                s.accept(event);
            } catch (Exception e) {
                System.err.println("[HarnessContext] 事件订阅者异常: key=" + key + ", error=" + e);
            }
        }
    }

    /**
     * 登记任意可逆副作用（Cordis ctx.effect）：定时器、缓存、进程句柄等。
     */
    public Effect effect(String description, Runnable disposer) {
        Effect effect = new Effect(pluginName, description, disposer);
        if (collector != null) {
            collector.collect(effect);
        }
        return effect;
    }

    /** 当前挂载插件名（根上下文为 null）。 */
    public String pluginName() {
        return pluginName;
    }
}
