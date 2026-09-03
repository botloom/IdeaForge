package cn.bitloom.harness.kernel;

/**
 * 类型化事件键 — Cordis 类型化事件的 Java 对应物。
 *
 * @param <E> 事件负载类型
 */
public final class EventKey<E> {

    private final String name;
    private final Class<E> type;

    private EventKey(String name, Class<E> type) {
        this.name = name;
        this.type = type;
    }

    public static <E> EventKey<E> of(String name, Class<E> type) {
        return new EventKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<E> type() {
        return type;
    }

    @Override
    public String toString() {
        return "EventKey[" + name + "]";
    }
}
