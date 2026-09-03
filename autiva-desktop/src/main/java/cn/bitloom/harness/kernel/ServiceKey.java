package cn.bitloom.harness.kernel;

/**
 * 类型化服务键 — Cordis ctx.<key> 的 Java 对应物。
 * <p>
 * 以常量形式声明内核各插件提供/注入的服务契约，避免裸字符串键的类型丢失：
 * <pre>
 * public static final ServiceKey&lt;ToolRegistry&gt; TOOLS = ServiceKey.of("tools");
 * ToolRegistry tools = ctx.inject(TOOLS);
 * </pre>
 *
 * @param <T> 服务类型
 */
public final class ServiceKey<T> {

    private final String name;
    private final Class<T> type;

    private ServiceKey(String name, Class<T> type) {
        this.name = name;
        this.type = type;
    }

    public static <T> ServiceKey<T> of(String name, Class<T> type) {
        return new ServiceKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "ServiceKey[" + name + "]";
    }
}
