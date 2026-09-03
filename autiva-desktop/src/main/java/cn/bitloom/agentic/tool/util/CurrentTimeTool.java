package cn.bitloom.agentic.tool.util;

import cn.bitloom.harness.tool.AbstractTool;
import cn.bitloom.harness.tool.ToolContext;
import cn.bitloom.harness.tool.ToolParam;
import cn.bitloom.harness.tool.ToolResult;
import org.apache.commons.lang3.StringUtils;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 当前时间工具 — 给智能体提供「时钟」能力（默认模型无法感知现实时间）。
 * <p>
 * 纯函数、无副作用、无依赖：返回当前时刻的 ISO-8601 格式化时间、Unix 时间戳，
 * 支持指定 IANA 时区与自定义输出格式。挂载在装配层插件集（WorkProfile）中，
 * 作为「一切皆插件」下新增真实工具的示例。
 */
public class CurrentTimeTool extends AbstractTool<CurrentTimeTool.Input> {

    private static final String DEFAULT_PATTERN = "yyyy-MM-dd'T'HH:mm:ssXXX";

    private static final String DESCRIPTION = """
            当前时间。返回此刻的日期时间与 Unix 时间戳（模型无法自行感知现实时钟，需要确定
            「现在是什么时间」时调用）。可指定 IANA 时区与输出格式；无参调用返回系统本地时区
            的 ISO-8601 时间。
            """;

    public record Input(
            @ToolParam(description = "IANA 时区 ID（如 Asia/Shanghai、UTC、America/New_York）；缺省用系统本地时区",
                    required = false) String timezone,
            @ToolParam(description = "输出格式（DateTimeFormatter 模式，如 yyyy-MM-dd HH:mm:ss）；缺省为 ISO-8601",
                    required = false) String pattern
    ) {}

    public CurrentTimeTool() {
        super("CurrentTime", DESCRIPTION, Input.class);
    }

    @Override
    public ToolResult execute(Input input, ToolContext context) {
        ZoneId zone = ZoneId.systemDefault();
        if (input.timezone() != null && !input.timezone().isBlank()) {
            try {
                zone = ZoneId.of(input.timezone().trim());
            } catch (DateTimeException e) {
                return ToolResult.error("未知时区 ID: " + input.timezone()
                        + "（应为 IANA 时区，如 Asia/Shanghai、UTC）");
            }
        }
        String pattern = StringUtils.isBlank(input.pattern())
                ? DEFAULT_PATTERN : input.pattern().trim();
        final DateTimeFormatter formatter;
        try {
            formatter = DateTimeFormatter.ofPattern(pattern);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("非法时间格式 pattern: " + pattern + "（" + e.getMessage() + "）");
        }

        ZonedDateTime now = ZonedDateTime.now(zone);
        String formatted = now.format(formatter);
        long epochMillis = now.toInstant().toEpochMilli();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("formatted", formatted);
        data.put("iso8601", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        data.put("epoch_millis", epochMillis);
        data.put("epoch_seconds", epochMillis / 1000);
        data.put("timezone", zone.getId());
        data.put("weekday", now.getDayOfWeek().getDisplayName(
                java.time.format.TextStyle.FULL, java.util.Locale.CHINESE));

        String raw = "当前时间（时区 " + zone.getId() + "）: " + formatted
                + "\nISO-8601: " + data.get("iso8601")
                + "\nUnix 时间戳(秒): " + (epochMillis / 1000)
                + "\nUnix 时间戳(毫秒): " + epochMillis
                + "\n星期: " + data.get("weekday");

        return ToolResult.success("当前时间: " + formatted, data, raw);
    }
}
