# 工具面（M4）：能力、风险等级与配置

本文列出内置工具的风险等级、默认状态与配置方法。**所有有副作用的工具默认关闭**（白名单为空即不可用）——默认拒绝，而不是默认放通。

## 1. 工具总览

| 工具 | 风险 | 默认状态 | 参数 | 说明 |
|---|---|---|---|---|
| `echo` | LOW | 可用 | text | 回显，用于验证工具链路 |
| `time` | LOW | 可用 | zone | 当前时间 |
| `http_get` | MEDIUM | 仅本机 | url | 通用 GET；主机白名单默认 `127.0.0.1,localhost` |
| `smart_home` | MEDIUM | 关闭 | device / action / value | 智能家居 HTTP 桥接（Home Assistant 类网关） |
| `mqtt_publish` | MEDIUM | 关闭 | topic / payload | **MQTT 直连发布**；主题前缀白名单默认空＝默认拒绝 |
| `web_read` | MEDIUM | 仅本机 | url | 抓取并提取标题/正文/链接（**不执行 JavaScript**） |
| `ssh_exec` | HIGH | 关闭 | host / command | 主机白名单 + 命令正则白名单；不经本地 shell |
| `web_submit` | HIGH | 仅本机 | url / body | 网页提交（写操作，不可撤销风险） |

MEDIUM/HIGH 工具在没有审批令牌时**不会被真正调用**，只会在 `/api/v1/pending-confirmations` 登记一条待确认记录。

## 2. 配置

| 配置键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `hive.agent.http-allow-hosts` | — | `127.0.0.1,localhost` | `http_get` 主机白名单 |
| `hive.agent.mqtt-broker-url` | `HIVE_MQTT_BROKER_URL` | 空（关闭） | 例如 `tcp://127.0.0.1:1883` |
| `hive.agent.mqtt-client-id` | — | `hive-mind` | 客户端 ID 前缀（实际连接会加随机后缀，避免重连冲突） |
| `hive.agent.mqtt-username` / `mqtt-password` | — | 空 | 可选认证 |
| `hive.agent.mqtt-topic-allow-prefixes` | `HIVE_MQTT_TOPIC_ALLOW_PREFIXES` | 空（默认拒绝） | 允许发布的主题前缀，逗号分隔 |
| `hive.agent.mqtt-qos` | — | 1 | 发布 QoS（0/1/2） |
| `hive.agent.ssh-hosts` | `HIVE_SSH_HOSTS` | 空（禁用） | 允许的 SSH 主机 |
| `hive.agent.ssh-user` | `HIVE_SSH_USER` | 空 | 登录用户（**命令里不允许指定 user@host**，避免绕过白名单） |
| `hive.agent.ssh-port` / `ssh-identity-file` | — | 22 / 空 | 端口与私钥 |
| `hive.agent.ssh-command-allowlist` | — | 空（一律拒绝） | 命令正则列表，逐条对**整条命令**匹配 |
| `hive.agent.ssh-timeout-millis` | — | 10000 | 超时后强制终止进程 |
| `hive.agent.web-allow-hosts` | `HIVE_WEB_ALLOW_HOSTS` | `127.0.0.1,localhost` | 网页工具主机白名单 |
| `hive.agent.web-max-bytes` | — | 200000 | 抓取/提交体积上限 |

## 3. 示例

```yaml
hive:
  agent:
    mqtt-broker-url: tcp://mqtt-broker.internal:1883
    mqtt-topic-allow-prefixes: home/living_room/,home/kitchen/
    mqtt-qos: 1
    ssh-hosts: ops-node.internal
    ssh-user: ops
    ssh-command-allowlist:
      - "uptime"
      - "df\\s+-h"
      - "systemctl status \\w+"
    web-allow-hosts: 127.0.0.1,localhost,docs.internal
```

调用示例（Agent 视角）：

```json
{"action":"tool","tool":"mqtt_publish","args":{"topic":"home/living_room/light/set","payload":"ON"},"thought":"开客厅灯"}
{"action":"tool","tool":"ssh_exec","args":{"host":"ops-node.internal","command":"uptime"},"thought":"看负载"}
{"action":"tool","tool":"web_read","args":{"url":"http://127.0.0.1:8101/api/v1/models"},"thought":"读本机接口"}
```

## 4. 拒绝时的表现（都是可读的理由，不是静默失败）

| 场景 | 返回 |
|---|---|
| MQTT 未配置 broker | `FAILED: 未配置 MQTT broker（hive.agent.mqtt-broker-url 或环境变量 HIVE_MQTT_BROKER_URL）` |
| 主题不在白名单 | `FAILED: 主题 home/bedroom/light/set 不在白名单内（允许的前缀=[...]）；白名单为空表示默认拒绝...` |
| SSH 未配置 | `FAILED: SSH 执行器未启用（需要配置 hive.agent.ssh-hosts 与 hive.agent.ssh-user）` |
| 命令不在白名单 | `FAILED: 命令不在白名单内：rm -rf /（允许的正则=[uptime, df\s+-h]）` |
| 主机不在白名单 | `FAILED: 主机 other-node.internal 不在白名单内（允许=[ops-node.internal]）` |
| 无审批令牌 | 工具不会被调用，登记到 `/api/v1/pending-confirmations`，理由为"MEDIUM/HIGH 风险工具需要审批令牌（approve=true）" |

## 5. 为什么这样设计

1. **默认拒绝**：白名单为空就是不可用。默认放通的"通用执行器"迟早会被提示注入当成内网跳板。
2. **参数不覆盖身份**：SSH 的用户与主机来自配置，模型只能选主机名（且必须命中白名单），不能自己拼 `user@host` 或加 `-o` 参数。
3. **不经本地 shell**：命令数组直接传给 `ssh`，命令字符串由远端解释；本地不做字符串拼接执行。
4. **写操作定 HIGH**：写外部状态（提交表单、控制设备）失败常不可逆，必须有人在场（审批令牌）。
5. **自带超时与体积上限**：工具能把整个 Agent 拖死是常见的工程事故，超时与上限是硬约束。
6. **诚实标注能力边界**：`web_read` 不执行 JavaScript、不携带登录态；需要这些能力的场景应上真实浏览器，而不是在这里假装支持。

## 6. 自改插件与内置工具的权限差异

项目治理规则（`Reviewer`）禁止**自改生成的插件**使用 `ProcessBuilder`、反射、文件删除等能力；
而 `ssh_exec` 这类内置工具是内核的一部分，用受策略约束的进程调用属于设计内能力。

这不是双标，而是**权限分层**：

| 主体 | 能不能起进程 | 约束 |
|---|---|---|
| 自改生成的插件 | 不能 | 静态黑名单 + import 白名单 + 测试门禁 + 冒烟 + 可回滚 |
| 内置工具（本仓代码） | 能 | 主机/命令/主题白名单 + 风险分级审批 + 超时 + 审计 + 人工评审才可能改它 |

换句话说：**只有经过人工评审的代码才能拿到"起进程"这种权限**，模型生成的代码不行。
