package cn.bitloom.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 应用调度器 — 用 JDK {@link ScheduledThreadPoolExecutor} 替代 Spring 的
 * {@code TaskScheduler}，并内置标准 6 字段 cron 表达式的"下一次触发时间"计算。
 * <p>
 * 支持 once（延迟一次）、interval（固定速率）、fixedDelay（固定延迟）、cron 四种调度。
 */
public final class AppScheduler implements AutoCloseable {

    private final ScheduledThreadPoolExecutor executor;

    public AppScheduler(int poolSize, String threadPrefix) {
        AtomicInteger seq = new AtomicInteger(0);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, threadPrefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = new ScheduledThreadPoolExecutor(poolSize, factory);
        this.executor.setRemoveOnCancelPolicy(true);
    }

    /** 延迟执行一次。 */
    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** 固定速率周期执行（initialDelay 后首次，之后每 period）。 */
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        return executor.scheduleAtFixedRate(task, initialDelay.toMillis(), period.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 固定延迟周期执行（上次完成后再延迟 delay）。 */
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration initialDelay, Duration delay) {
        return executor.scheduleWithFixedDelay(task, initialDelay.toMillis(), delay.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** cron 调度：每次触发后计算下一次时间并重新注册。 */
    public ScheduledFuture<?> scheduleCron(Runnable task, String cronExpr) {
        return scheduleCron(task, cronExpr, Instant.now());
    }

    private ScheduledFuture<?> scheduleCron(Runnable task, String cronExpr, Instant after) {
        Instant next = CronSupport.nextTrigger(cronExpr, after);
        Duration delay = Duration.between(Instant.now(), next);
        if (delay.isNegative()) {
            delay = Duration.ZERO;
        }
        return executor.schedule(() -> {
            try {
                task.run();
            } finally {
                scheduleCron(task, cronExpr, Instant.now());
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    /**
     * 标准 6 字段 cron 的"下一次触发时间"计算（秒 分 时 日 月 周）。
     * 支持 {@code *}、具体值、{@code -} 范围、{@code /} 步长、{@code ,} 列表；
     * 周字段 0 与 7 均视为周日。返回 after 之后（不含 after）的最近匹配时间。
     */
    public static final class CronSupport {

        private CronSupport() {
        }

        public static Instant nextTrigger(String cronExpr, Instant after) {
            String[] fields = cronExpr.trim().split("\\s+");
            if (fields.length < 6 || fields.length > 7) {
                throw new IllegalArgumentException("cron 表达式需为 6 字段（秒 分 时 日 月 周）: " + cronExpr);
            }
            CronField second = parse(fields[0], 0, 59, null);
            CronField minute = parse(fields[1], 0, 59, null);
            CronField hour = parse(fields[2], 0, 23, null);
            CronField dayOfMonth = parse(fields[3], 1, 31, null);
            CronField month = parse(fields[4], 1, 12, new String[]{
                    "JAN", "FEB", "MAR", "APR", "MAY", "JUN",
                    "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"});
            CronField dayOfWeek = parse(fields[5], 0, 7, new String[]{
                    "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"});

            ZonedDateTime base = after.atZone(ZoneId.systemDefault()).withNano(0).plusSeconds(1);
            // 最多向前扫描 4 年，避免死循环
            ZonedDateTime limit = base.plusYears(4);
            for (ZonedDateTime t = base; t.isBefore(limit); t = t.plusSeconds(1)) {
                if (month.matches(t.getMonthValue())
                        && dayOfMonth.matches(t.getDayOfMonth())
                        && dayOfWeek.matches(t.getDayOfWeek().getValue() % 7)
                        && hour.matches(t.getHour())
                        && minute.matches(t.getMinute())
                        && second.matches(t.getSecond())) {
                    return t.toInstant();
                }
            }
            throw new IllegalArgumentException("cron 表达式在 4 年内无匹配时间: " + cronExpr);
        }

        private static CronField parse(String expr, int min, int max, String[] names) {
            List<Integer> values = new ArrayList<>();
            for (String part : expr.split(",")) {
                if ("*".equals(part) || "?".equals(part)) {
                    for (int i = min; i <= max; i++) {
                        values.add(i);
                    }
                    continue;
                }
                int step = 1;
                String base = part;
                int slash = part.indexOf('/');
                if (slash >= 0) {
                    base = part.substring(0, slash);
                    step = Integer.parseInt(part.substring(slash + 1));
                }
                if (base.contains("-")) {
                    String[] range = base.split("-");
                    int start = resolve(range[0], names, min);
                    int end = resolve(range[1], names, max);
                    for (int i = start; i <= end; i += step) {
                        values.add(i);
                    }
                } else {
                    values.add(resolve(base, names, min));
                }
            }
            return new CronField(values);
        }

        private static int resolve(String token, String[] names, int fallback) {
            if (names != null) {
                String upper = token.toUpperCase();
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(upper)) {
                        return i + 1;
                    }
                }
            }
            return Integer.parseInt(token);
        }

        private record CronField(List<Integer> values) {
            boolean matches(int actual) {
                return values.contains(actual);
            }
        }
    }
}
