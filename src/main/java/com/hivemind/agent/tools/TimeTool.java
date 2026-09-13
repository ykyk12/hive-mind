package com.hivemind.agent.tools;

import com.hivemind.agent.RiskLevel;
import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** 当前时间（只读、无副作用）。 */
public final class TimeTool implements Tool {

    @Override
    public String name() {
        return "time";
    }

    @Override
    public String description() {
        return "返回指定时区的当前时间（默认 Asia/Shanghai）";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.LOW;
    }

    @Override
    public Map<String, String> parameterSchema() {
        return Map.of("zone", "时区 ID，例如 Asia/Shanghai");
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        Object zone = args.get("zone");
        String zoneId = zone == null || String.valueOf(zone).isBlank() ? "Asia/Shanghai" : String.valueOf(zone);
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zoneId));
            return ToolResult.ok(zoneId + " 当前时间 " + now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        } catch (RuntimeException e) {
            return ToolResult.fail("时区不合法：" + zoneId);
        }
    }
}
