package com.hivemind.agent.tools;

import com.hivemind.agent.RiskLevel;
import com.hivemind.agent.Tool;
import com.hivemind.agent.ToolContext;
import com.hivemind.agent.ToolResult;
import com.hivemind.config.HiveProperties;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * SSH 执行器（在自己的电脑/服务器上跑命令）。
 *
 * 这是全项目最危险的工具，所以护栏最多、默认关闭：
 * - 默认（hosts 白名单为空）**完全不可用**，必须显式配置主机；
 * - 主机白名单 + 用户固定（不允许参数里指定 user@host，避免绕过白名单）；
 * - 命令必须命中**正则白名单**（逐条匹配整条命令），白名单为空即一律拒绝；
 * - HIGH 风险 → 必须带审批令牌；参数走数组传递，不经过本地 shell 解释；
 * - 超时强杀 + BatchMode（不交互、不因缺密钥卡住）。
 *
 * 说明：项目治理规则禁止**自改生成的插件**使用 ProcessBuilder（见 Reviewer 黑名单），
 * 但内置工具本身就是内核的一部分，它用受策略约束的进程调用是设计内的能力，两者不是一回事。
 */
@Slf4j
public final class SshExecTool implements Tool {

    private final HiveProperties properties;

    public SshExecTool(HiveProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "ssh_exec";
    }

    @Override
    public String description() {
        return "在受信任主机上执行白名单内的命令。参数：host 主机，command 命令（必须命中命令白名单）";
    }

    @Override
    public RiskLevel risk() {
        return RiskLevel.HIGH;
    }

    @Override
    public Map<String, String> parameterSchema() {
        Map<String, String> schema = new java.util.LinkedHashMap<>();
        schema.put("host", "白名单内的主机");
        schema.put("command", "要执行的命令，例如 uptime");
        return schema;
    }

    @Override
    public ToolResult invoke(ToolContext context, Map<String, Object> args) {
        HiveProperties.Agent config = properties.getAgent();
        List<String> allowedHosts = HiveProperties.splitCsv(config.getSshHosts());
        if (allowedHosts.isEmpty() || config.getSshUser().isBlank()) {
            return ToolResult.fail("SSH 执行器未启用（需要配置 hive.agent.ssh-hosts 与 hive.agent.ssh-user）");
        }
        String host = String.valueOf(args.getOrDefault("host", "")).trim();
        String command = String.valueOf(args.getOrDefault("command", "")).trim();
        if (host.isBlank() || command.isBlank()) {
            return ToolResult.fail("host 与 command 均为必填");
        }
        if (allowedHosts.stream().noneMatch(host::equalsIgnoreCase)) {
            return ToolResult.fail("主机 " + host + " 不在白名单内（允许=" + allowedHosts + "）");
        }
        List<String> patterns = config.getSshCommandAllowlist();
        if (patterns.isEmpty()) {
            return ToolResult.fail("命令白名单为空：默认拒绝一切命令（hive.agent.ssh-command-allowlist）");
        }
        boolean permitted = patterns.stream().anyMatch(pattern -> matches(pattern, command));
        if (!permitted) {
            return ToolResult.fail("命令不在白名单内：" + command + "（允许的正则=" + patterns + "）");
        }

        List<String> commandLine = new ArrayList<>();
        commandLine.add("ssh");
        commandLine.add("-o");
        commandLine.add("BatchMode=yes");
        commandLine.add("-o");
        commandLine.add("ConnectTimeout=5");
        commandLine.add("-o");
        commandLine.add("StrictHostKeyChecking=accept-new");
        commandLine.add("-p");
        commandLine.add(String.valueOf(config.getSshPort()));
        if (!config.getSshIdentityFile().isBlank()) {
            commandLine.add("-i");
            commandLine.add(config.getSshIdentityFile());
        }
        // 用户与主机来自配置，不允许由模型指定，避免"换个 user@host 绕过白名单"
        commandLine.add(config.getSshUser() + "@" + host);
        commandLine.add(command);

        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(commandLine);
            builder.redirectErrorStream(true);
            process = builder.start();
            String output = readAll(process.getInputStream(), 20000);
            boolean finished = process.waitFor(config.getSshTimeoutMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.fail("SSH 命令超时（>" + config.getSshTimeoutMillis() + "ms），已强制终止");
            }
            int exitCode = process.exitValue();
            log.info("SSH 执行 host={} exit={} 命令={}（任务 {}）", host, exitCode, command, context.taskId());
            if (exitCode != 0) {
                return ToolResult.fail("命令退出码 " + exitCode + "，输出：" + output);
            }
            return ToolResult.ok(output.isBlank() ? "（无输出，退出码 0）" : output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.fail("SSH 执行被中断");
        } catch (IOException e) {
            return ToolResult.fail("无法执行 ssh 客户端：" + e.getMessage()
                    + "（运行环境需要安装 OpenSSH 客户端与可用密钥）");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean matches(String regex, String command) {
        try {
            return Pattern.compile(regex).matcher(command).matches();
        } catch (PatternSyntaxException e) {
            log.warn("SSH 命令白名单正则不合法，已按拒绝处理：{}", regex);
            return false;
        }
    }

    private String readAll(InputStream in, int maxChars) throws IOException {
        byte[] bytes = in.readNBytes(maxChars);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
