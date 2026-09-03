package cn.bitloom.util;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 轻量类型化事件总线 — 替换 Spring 的 {@code ApplicationContext.publishEvent} /
 * {@code @EventListener} 机制（无 Spring 依赖）。
 * <p>
 * 订阅按事件类型分发；单个订阅者异常不影响其它订阅者。用于自修改事件等应用内
 * 广播场景，替代 {@link SpringContextUtil#publishEvent}。
 */
public final class AppEvents {

    private static final Map<Class<?>, List<Consumer<Object>>> LISTENERS = new ConcurrentHashMap<>();

    private AppEvents() {
    }

    /** 订阅指定类型的事件。 */
    @SuppressWarnings("unchecked")
    public static <T> void subscribe(Class<T> type, Consumer<T> listener) {
        List<Consumer<Object>> list = LISTENERS.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(event -> listener.accept((T) event));
    }

    /** 发布事件，同步通知所有同类型订阅者。 */
    @SuppressWarnings("unchecked")
    public static <T> void publish(T event) {
        if (event == null) {
            return;
        }
        List<Consumer<Object>> list = LISTENERS.get(event.getClass());
        if (list == null) {
            return;
        }
        for (Consumer<Object> listener : list) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                System.err.println("[AppEvents] 订阅者异常: type=" + event.getClass().getSimpleName()
                        + ", error=" + e);
            }
        }
    }

    /** 清空所有订阅（测试/重启用）。 */
    public static void clear() {
        LISTENERS.clear();
    }
}
