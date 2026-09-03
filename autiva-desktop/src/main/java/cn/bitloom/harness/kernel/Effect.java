package cn.bitloom.harness.kernel;

/**
 * 可逆注册（Cordis Effect 的 Java 对应物）。
 * <p>
 * 一切"挂到 ctx 上的东西"（服务、事件订阅、缓存条目、定时器……）都以 Effect 返回。
 * Effect 持有撤销逻辑（disposer），close() 幂等执行。插件卸载时，其全部 Effect
 * 按注册的逆序回滚——这是"卸载不留残留 / 运行中改写自己"的机制前提。
 */
public final class Effect implements AutoCloseable {

    private final String pluginName;
    private final String description;
    private final Runnable disposer;
    private boolean disposed = false;

    public Effect(String pluginName, String description, Runnable disposer) {
        this.pluginName = pluginName;
        this.description = description;
        this.disposer = disposer;
    }

    /** 静态空 Effect（无需撤销的注册）。 */
    public static Effect noop(String pluginName, String description) {
        return new Effect(pluginName, description, () -> { });
    }

    public String pluginName() {
        return pluginName;
    }

    public String description() {
        return description;
    }

    /** 是否已回滚。 */
    public synchronized boolean isDisposed() {
        return disposed;
    }

    /** 回滚本注册（幂等，异常吞掉只记日志——回滚不中断卸载流程）。 */
    @Override
    public synchronized void close() {
        if (disposed) {
            return;
        }
        disposed = true;
        try {
            disposer.run();
        } catch (Exception e) {
            System.err.println("[Effect] 回滚失败 (plugin=" + pluginName + ", effect=" + description + "): " + e);
        }
    }

    @Override
    public String toString() {
        return "Effect[" + pluginName + ":" + description + "]";
    }
}
