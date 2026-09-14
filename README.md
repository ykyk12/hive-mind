# HiveMind —— 同构蜂巢智能体平台

> 一句话：**同一份代码，既是"大脑"也是"神经元"；能调用多家模型各取所长；能把经验蒸馏成可版本化的技能并在节点间传播；能改写自己的插件与配置，但改写必须过"提议者—审核者—门锁"三权分立，且永远可回滚。**

技术栈：Java 17 · Spring Boot 3.3 · 纯 JDK HttpClient（不引任何 LLM SDK）· Micrometer/Actuator · springdoc-openapi · Docker Compose · GitHub Actions

当前版本 **1.1.1（M0 内核 + M1 集群 + M2 经验面 + M3 测试门禁 + M4 工具面）**：单 jar 多进程可跑通，无需数据库、无需外部 API（内置本地确定性模型）。

---

## 1. 这个项目在做什么

你想要的"蜂巢思维"不是一个功能，而是五个可以在工程上落地的机制。本项目把它们分开实现、分开验证：

| 设想中的说法 | 工程等价物 | 本仓实现 |
|---|---|---|
| 所有神经元都能当大脑 | 同构节点 + 租约选举 + 多数派 | `cluster/`：term 单调 + quorum + 随机选举超时 + 大脑租约 |
| 同时接入多种 API，各取所长 | 能力画像 + 路由 + 降级链 + 断熔 | `model/`：画像打分、在线成功率 EMA、断熔器、多模型并列对比 |
| 作为神经元吸收经验、优化自身 | 经验蒸馏成**可版本化技能制品** | `skill/`：`SkillArtifact` 版本化 + 适应度 EMA + 召回注入 |
| 反馈给大脑，大脑进化神经元 | 反熵传播 + **跨节点验证才推广** | `skill/`+`cluster/`：gossip 只广播"多节点验证过"的技能 |
| 自行修改代码、自动进化 | **提议 → 审核 → 门锁 → 隔离编译 → 冒烟 → 原子切换 → 回滚** | `govern/`+`evolve/`：三权分立 + 确定性门禁 + 制品指针 |
| 连接智能家居、电脑 | Tool 契约 + 风险分级 + 确认门 | `agent/tools/`：MQTT/HTTP 桥接、主机白名单、MEDIUM/HIGH 需审批 |

**明确不做的事**（也是这个项目可信的地方）：

- 不让模型直接改内核源码并热替换——内核变更一律要求人工签名，且不自动应用；
- 不做"没有适应度函数的自动进化"——没有方向声明（预期收益）的变更，门锁直接否决；
- 不声称"适配所有场景"——正确表述是**内核稳定、能力可插**：新场景 = 加一个 Tool 插件，而不是改内核。

---

## 2. 核心能力

| 能力 | 位置 | 说明 |
|---|---|---|
| 同构节点 + 选举 | `cluster/ElectionService` | 启动一律为 NEURON，心跳超时后按 term 竞选；无多数派不许自任大脑 |
| 大脑租约 | `cluster/HeartbeatService` | 大脑只有拿到多数派确认才续租，否则自动卸任（防分裂脑） |
| 任务下发 | `cluster/TaskDispatcher` | 大脑按能力把任务派给神经元，失败自动回退本地执行 |
| 多模型路由 | `model/ModelRouter` | 排序因子 = 能力匹配 + 成功率 EMA + 权重 − 成本 − 延迟；失败走降级链 |
| 断熔 | `model/CircuitBreaker` | 连续失败即 OPEN，冷却后半开，避免每次调用白等一次超时 |
| Agent 循环 | `agent/AgentLoop` | 模型 → 工具 → 观察 → 模型（JSON 协议，宽松解析），步数上限保护 |
| 风险门 | `agent/ConfirmationGate` | LOW 直接执行；MEDIUM/HIGH 必须带审批令牌，被拦下时登记待确认 |
| 技能库 | `skill/SkillStore` | 版本不可变、适应度 EMA、召回按"相关度 × 适应度" |
| 经验蒸馏 | `skill/ExperienceBus` | 成功且多步的任务才蒸馏；失败轨迹只统计不固化（避免把错误固化成经验） |
| 经验传播 | `cluster/GossipService` | 只广播"≥2 个节点验证 + 适应度 + 使用次数"达标的技能，防崩溃传染 |
| 三权分立 | `govern/` | 提议者（模型）不能自审、不能自签；审核者不能放行；门锁才有最终放行权 |
| 门锁（确定性） | `govern/Governor` | 否决/升级人工/方向控制三条线，全程无模型参与，避免被提示注入说服 |
| 隔离进化 | `evolve/EvolutionPipeline` | `javax.tools` 隔离编译 → 子优先 ClassLoader 加载 → 冒烟 → 原子注册 → 可回滚 |
| **技能影子对比** | `skill/SkillShadowService` | 同一输入"注入经验 vs 不注入"双跑对比，判定函数可替换；**只更新适应度、不计入验证节点** |
| **适应度淘汰** | `skill/SkillCurator`、`SkillStore.retire` | 用够了但适应度掉到阈值以下 → 自动退役（停止召回与广播），历史与统计保留、可恢复 |
| **召回质量评估** | `skill/SkillRecallEvaluation` + `eval/skill-recall-cases.json` | 固定夹具上算 precision@k，改召回算法后可直接对比，指标可回归 |
| **真实测试门禁（M3）** | `evolve/CandidateTestRunner` | 候选插件自带的单元测试在**隔离类加载器**里用 JUnit Platform Launcher 真实执行，未过不许生效 |
| **MQTT 直连（M4）** | `agent/tools/MqttPublishTool` | 直接发布到 broker（不再依赖 HTTP 桥）；主题前缀白名单**默认空＝默认拒绝** |
| **SSH 执行器（M4）** | `agent/tools/SshExecTool` | 主机白名单 + 命令正则白名单（默认空＝禁用）+ HIGH 风险审批 + 超时强杀，不经本地 shell |
| **网页读写（M4）** | `agent/tools/WebReaderTool`、`WebSubmitTool` | 抓取并提取标题/正文/链接（不执行 JS）；提交为 HIGH 风险写操作；主机白名单 + 体积上限 |

## 3. 架构

```
                    ┌──────────── 治理面（需 X-Hive-Admin-Key）────────────┐
 目标 / 人工提案 ──► │ Proposer(模型) → Reviewer(规则) → Governor(纯确定性) │
                    │        ↓ 隔离编译 → 冒烟 → 制品归档 → 指针切换        │
                    └───────────────┬──────────────────────────────────────┘
                                    │ 生效制品（可一键回滚）
                    ┌───────────────▼───────────────┐
                    │  节点运行时（同构，处处相同）   │
  客户端 ──► /tasks │ AgentLoop → ModelRouter → Tool │ ──► /api/v1/models
                    │       ↑ SkillAdvisor 召回       │
                    └───┬───────────────────────┬───┘
                        │ 经验上报 TaskTrace     │ 工具调用（含风险门）
        ┌───────────────▼──────────┐   ┌────────▼─────────────────────┐
        │ 经验面：蒸馏/适应度/门槛   │   │ 工具面：echo/time/http/家居   │
        └───────────────┬──────────┘   └──────────────────────────────┘
                        │ 只广播"跨节点验证过"的技能（反熵）
     ┌──────────────────▼───────────────────────────────────────────┐
     │ 集群面：term 选举 + 多数派 + 租约 + 心跳 + 任务下发（/cluster） │
     └──────────────────────────────────────────────────────────────┘
```

数据流（一次任务）：`/tasks` → 召回技能 → 模型输出 JSON 动作 → 风险门 → 工具执行 → 观察回灌 → 最终答案 → 轨迹上报经验面 → 达标的经验才可能被推广。

## 4. 快速开始（零外部依赖）

```bash
# 需要 JDK 17+ 与 Maven；默认使用内置本地确定性模型，不需要任何 API Key
mvn spring-boot:run
```

```bash
# 1) 跑一次任务（本地模型会按协议调用 echo 工具，再基于结果作答）
curl -H "Content-Type: application/json" \
     -d '{"input":"CALL_TOOL:echo {\"text\":\"hello hive\"}"}' \
     http://localhost:8101/api/v1/tasks

# 2) 中风险工具会被风险门拦下（不带审批令牌）
curl -H "Content-Type: application/json" \
     -d '{"input":"CALL_TOOL:smart_home {\"device\":\"living_room_light\",\"action\":\"on\"}"}' \
     http://localhost:8101/api/v1/tasks
curl http://localhost:8101/api/v1/pending-confirmations

# 3) 模型编排：当前会选谁、为什么、各家健康度
curl http://localhost:8101/api/v1/models

# 4) 集群状态（单节点会在第一个心跳周期内自动当选大脑）
curl http://localhost:8101/api/v1/cluster/state
```

接入真实模型（DeepSeek / 通义 / 自建 vLLM 等 OpenAI 兼容协议）：

```bash
export DEEPSEEK_API_KEY=sk-xxx          # 不配置就只走本地模型，不会报错
mvn spring-boot:run
```

## 5. 三节点集群（一台机器即可演示）

```bash
# 方式一：docker compose（推荐）
docker compose up --build

# 方式二：三个终端
HIVE_NODE_ID=node-1 HIVE_PORT=8101 HIVE_ENDPOINT=http://127.0.0.1:8101 \
HIVE_PEERS=http://127.0.0.1:8102,http://127.0.0.1:8103 mvn spring-boot:run
# node-2 → 8102，node-3 → 8103（HIVE_PEERS 填另外两个地址）

# 观察：谁当大脑、term 多少、看见了哪些节点
curl http://localhost:8101/api/v1/cluster/state

# 杀掉当前大脑进程，再看另一个节点：它会在租约到期后自动接管
curl http://localhost:8102/api/v1/cluster/state
```

`HIVE_CLUSTER_TOKEN` 配置后，节点间接口必须带 `X-Hive-Cluster-Token`；不配置则仅适合本机演示。

## 6. 自改流程（治理演示）

```bash
KEY="X-Hive-Admin-Key: hive-admin-key"

# 1) 提议者（模型）生成提案：补一个工具插件
curl -H "$KEY" -H "Content-Type: application/json" \
     -d '{"goal":"补一个零依赖的文本统计工具"}' \
     http://localhost:8101/api/v1/governance/proposals/generate

# 2) 让提议者自己审 → 被拒（自审禁令）
curl -H "$KEY" -H "Content-Type: application/json" -d '{"reviewer":"proposer-agent"}' \
     http://localhost:8101/api/v1/governance/proposals/{id}/review

# 3) 换审核者 → 通过；再看门锁裁决
curl -H "$KEY" -H "Content-Type: application/json" -d '{"reviewer":"reviewer-agent"}' \
     http://localhost:8101/api/v1/governance/proposals/{id}/review
curl -H "$KEY" -X POST http://localhost:8101/api/v1/governance/proposals/{id}/gate

# 4) 应用（隔离编译 + 冒烟 + 原子注册），然后直接调用新工具
curl -H "$KEY" -X POST http://localhost:8101/api/v1/governance/proposals/{id}/apply
curl -H "Content-Type: application/json" \
     -d '{"input":"CALL_TOOL:text_stats {\"text\":\"hive mind\"}"}' \
     http://localhost:8101/api/v1/tasks

# 5) 改坏了就回滚（制品指针退回上一版本 / 配置恢复旧值）
curl -H "$KEY" -X POST "http://localhost:8101/api/v1/evolution/rollback?name=text_stats"
```

规则与判据的完整说明见 [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md)，工具与配置见 [`docs/TOOLS.md`](docs/TOOLS.md)。

## 6.1 经验面（M2）与测试门禁（M3）

```bash
# 影子对比：同一输入"注入经验 vs 不注入"，看是否真的更好
curl -H "Content-Type: application/json" \
     -d '{"input":"订单超时怎么办","skillId":"skill-order-timeout"}' \
     http://localhost:8101/api/v1/skills/shadow

# 召回质量评估：固定夹具上的 precision@k
curl "http://localhost:8101/api/v1/skills/eval?topK=4"

# 淘汰巡检：候选与已退役清单（?run=true 立即执行一次）
curl "http://localhost:8101/api/v1/skills/curation?run=true"

# 测试门禁：候选插件必须自带单元测试，且测试要在隔离类加载器里真实跑通
# 生成的插件已包含 GeneratedTextStatsToolTest，apply 时你会看到 "候选单元测试全部通过（2/2）"
curl -H "$KEY" -X POST http://localhost:8101/api/v1/governance/proposals/{id}/apply
```

测试门禁的失败长这样（**不会**静默放行）：

```json
{"success":true,"data":{"applied":false,
 "message":"测试门禁未通过：候选单元测试未通过（成功 0，失败 1）",
 "steps":["落盘候选源码：...","隔离编译：通过（源文件 2 个）","候选单元测试：候选单元测试未通过（成功 0，失败 1）"]}}
```

## 6.2 工具面（M4）

所有新增工具**默认关闭**：白名单为空即不可用，需要显式配置才打开。

```bash
# MQTT 直连（智能家居）
export HIVE_MQTT_BROKER_URL=tcp://127.0.0.1:1883
export HIVE_MQTT_TOPIC_ALLOW_PREFIXES=home/living_room/,home/kitchen/
# 之后可以让 Agent 执行（MEDIUM 风险 → 需要 approve=true）
#   CALL_TOOL:mqtt_publish {"topic":"home/living_room/light/set","payload":"ON"}

# SSH 执行器（自己的电脑/服务器）
export HIVE_SSH_HOSTS=ops-node.internal
export HIVE_SSH_USER=ops
# 命令白名单以配置形式给出（正则，逐条匹配整条命令），默认空＝一律拒绝
#   hive.agent.ssh-command-allowlist: ["uptime", "df\\s+-h", "systemctl status \\w+"]

# 网页读取（默认只允许本机；要访问外部站点需显式加白名单）
export HIVE_WEB_ALLOW_HOSTS=127.0.0.1,localhost,example.com
#   CALL_TOOL:web_read {"url":"https://example.com"}
```

MQTT / SSH / 网页提交都是 MEDIUM/HIGH 风险工具，**没有审批令牌不会真正执行**，且每次调用都进审计。

## 7. 关键设计决策

1. **角色是状态不是部署**：节点同构，才能做到"任意节点都能接管"；否则"蜂巢"退化成主从。
2. **必须有 term + quorum**：否则网络抖动会造出两个大脑同时指挥同一批节点。
3. **审核用规则、不用模型**：审核结论要可复现、可争论；让模型审模型，一次提示注入就能绕过门禁。
4. **门锁纯确定性**：能否放行只由策略决定，不由"谁说服了谁"决定。
5. **进化必须带方向声明**：没有预期收益就没有适应度，自动进化会退化成随机漂移。
6. **技能跨节点验证才推广**：单节点的坏经验扩散到全网 = 崩溃传染，这是同构集群的固有风险。
7. **运行中的实例不可变，切换是换指针**：候选代码在影子目录编译+冒烟，失败就地丢弃。
8. **不引 LLM SDK**：全部走 OpenAI 兼容 HTTP 协议，减少依赖冲突面（也方便接自建推理服务）。

## 8. 已知边界（诚实清单）

| 边界 | 现状 | 计划 |
|---|---|---|
| 状态持久化 | 节点/技能/审计均在内存，重启即清空 | 可切 Redis/PostgreSQL 的存储实现（接口已隔离） |
| 节点发现 | 靠 `peers` 配置 + 心跳补全 | 组播/gossip 自动发现 |
| 技能回滚 | 只切制品指针，技能库内容仍保留版本 | 按指针驱动召回 |
| 测试门禁 | **已实现**：隔离类加载器 + JUnit Launcher 真实跑候选单测（未过不许生效） | 覆盖率门槛、变异测试、按风险分级要求不同强度 |
| 内核自改 | **不支持自动应用**（故意） | 保持人工评审 + 发布流程 |
| 节点间认证 | 无 token 时放行（仅本机演示） | 生产必须配 `HIVE_CLUSTER_TOKEN`，并考虑双向 TLS |
| 网页读写 | 抓取+提取（**不执行 JavaScript、无登录态**） | 需要 JS 渲染/登录态的站点接入真实浏览器（Playwright 类），并加域名级配额 |
| SSH 执行器 | 依赖运行环境的 `openssh` 客户端与密钥 | 可选纯 Java SSH 实现，去掉外部命令依赖 |
| 影子对比区分度 | 用本地确定性模型时两组输出一致，必然平局 | 接真实模型后才有区分度；判定函数已是可替换策略 |
| 工具资源限制 | 无 CPU/内存墙（仅类加载隔离 + 静态检查 + 超时） | 独立进程/容器执行候选插件 |

## 9. 路线图

**已完成**

- **M0 内核**：多模型路由 + Agent 循环 + 工具与风险门 + 经验蒸馏
- **M1 集群**：同构节点、心跳租约、term 选举、反熵传播、任务下发
- **M2 经验面**：技能 A/B 影子对比、适应度驱动淘汰、召回质量评估集与 precision@k
- **M3 测试门禁**：隔离类加载器 + JUnit Platform Launcher 真实执行候选单测，未过不许生效
- **M4 工具面**：MQTT 直连、SSH 执行器、网页读写（均为默认关闭 + 白名单 + 审批 + 审计）

**下一步**

- **持久化与配额**：技能/审计/制品落 Redis 或 PostgreSQL，按租户限流
- **节点级金丝雀**：新制品先在一个节点上跑，指标不退化再扩散（避免"一个坏 patch 全网同时生效"）
- **更强隔离**：候选插件独立进程/容器执行，配合资源墙与超时；覆盖率门槛与变异测试
- **真实浏览器**：Playwright 类方案支持 JS 渲染与登录态网页（当前 `web_read` 只做静态提取）
- **指标看板**：把决策分布、经验适应度、门禁通过率做成可观测面板

## 10. 版本记录

| 版本 | 说明 |
|---|---|
| 1.1.2 | 模型路由韧性增强（对标 one-api / LiteLLM）：OpenAI 兼容提供方对 429/5xx/网络超时做指数退避重试（4xx 不重试，可配 `maxRetries`/`retryBaseMillis`）；单次请求超时可配（`requestTimeoutMillis`，原硬编码 90s）；断熔器半开态只放行一个并发探针，避免打挂恢复中的提供方。新增 `CircuitBreakerTest`、`OpenAiCompatibleProviderRetryTest`。 |
| 1.1.1 | 清理硬编码局域网 IP：测试与文档统一改用主机名（`ops-node.internal` / `mqtt-broker.internal`），避免把环境细节写死进仓库 |
| 1.1.0 | M2 经验面（影子对比 / 适应度淘汰 / 召回评估集）、M3 真实测试门禁（隔离类加载器 + JUnit Launcher，未过不许生效）、M4 工具面（MQTT 直连 / SSH 执行器 / 网页读写，默认关闭 + 白名单 + 审批 + 审计） |
| 1.0.0 | 首个功能提交（M0 内核 + M1 集群）：多模型路由与断熔、Agent 循环与风险门、技能蒸馏与反熵传播、三权分立门禁与隔离进化、心跳/选举/任务下发、测试与 CI |

## 10.1 对标升级（1.1.2）

**对标了哪些真实高星项目（仅借鉴设计思想，未逐字复制代码）：**

| 项目 | star 量级 | URL | 借鉴点 |
|---|---|---|---|
| one-api | ~20k+ | https://github.com/songquanpeng/one-api | 多渠道网关：5xx/429 自动重试、4xx 不重试、渠道失败降级 |
| LiteLLM | ~15k+ | https://github.com/BerriAI/litellm | `RetryPolicy`：`retryable_status_codes=[429,500,503]`、`backoff_factor` 指数退避、按错误类型区分重试 |

**吸收并实现：**

1. **同一提供方的瞬时失败重试（退避）**：`OpenAiCompatibleProvider` 原先一次失败就直接抛给路由降级。现借鉴 LiteLLM `RetryPolicy`，对 **429 限流 / 5xx / 网络超时**做指数退避重试（`base * 2^attempt`，封顶 2s）；**4xx（鉴权/参数/不存在）不重试**，避免无效重试。新增 `ModelUnavailableException.retryable()` 标记瞬时/确定性错误。
2. **请求超时可配**：原硬编码 90s 单次请求超时，现按提供方可配（`requestTimeoutMillis`，默认 90000，向后兼容）。
3. **断熔器半开单探针**：冷却结束后只放行**一个**并发探针请求，其余并发请求本轮直接降级——避免恢复中的提供方被并发探测瞬间再次打挂（经典断熔器语义）。

**改动文件：**

- `src/main/java/com/hivemind/model/OpenAiCompatibleProvider.java`（重试循环 + 可配超时 + 可测判定）
- `src/main/java/com/hivemind/model/CircuitBreaker.java`（半开单探针）
- `src/main/java/com/hivemind/model/ModelUnavailableException.java`（retryable 标记）
- `src/main/java/com/hivemind/config/HiveProperties.java`（`maxRetries`/`retryBaseMillis`/`requestTimeoutMillis`）
- `src/test/java/com/hivemind/model/CircuitBreakerTest.java`（新增）
- `src/test/java/com/hivemind/model/OpenAiCompatibleProviderRetryTest.java`（新增）
- `pom.xml`（1.1.1 → 1.1.2）、`README.md`（本节与版本表）

**验证命令与结果：**

```
mvn -B test
# Tests run: 73, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS
```

## 10.1 第二轮韧性加固（v1.1.3）

在 v1.1.2（指数退避 + 断熔半开单探针）的基础上，本轮做深度走查、修 bug 与工程化加固。

**修掉的 Bug（每个都带"位置 + 触发条件 + 回归测试 + 修复"）：**

1. **SkillStore 内层 List 读写竞态（ConcurrentModificationException）。**
   - 位置：`com.hivemind.skill.SkillStore`。`versions` 的 value 是普通 `ArrayList`，
     而 `publish`/`recordUsage` 是 `synchronized`（写），但 `active()`/`versions()`/`allActive()`/
     `upsertFromPeer()` 之前**没有** `synchronized`（读）。
   - 触发条件：集群反熵接收（`upsertFromPeer`）与本节点发布（`publish`）/使用上报（`recordUsage`）并发时，
     读线程在 `ArrayList` 上遍历时，写线程恰好 `list.add(0, ...)` 或 `list.set(i, ...)`，
     就会抛 `ConcurrentModificationException`，或读到撕裂的版本列表。
   - 修法：给上述读方法统一加 `synchronized`（与写方法共用同一把 `this` 监视器），
     读写在同一把锁上互斥，读快照自洽。
   - 回归测试：`skill/SkillStoreConcurrencyTest`（3 写线程并发 publish/recordUsage/upsert，
     4 读线程并发 active/versions/allActive/hintsFor，断言零异常且读自洽）。
2. **重试无抖动（retry storm / thundering herd）。**
   - 位置：`model/OpenAiCompatibleProvider`。退避是 `base * 2^attempt` 封顶 2s，
     但**没有任何随机抖动**：所有客户端在同一时刻按完全相同的间隔重试，
     下游刚一恢复就被齐射打垮。
   - 触发条件：多客户端同时遭遇 429/5xx 时，退避节奏完全一致。
   - 修法：新增 `applyJitter(backoff, random)`，采用**等抖动（equal jitter）**，
     实际等待落在 `[backoff/2, backoff]` 区间内随机；原确定性纯函数 `backoffMillis` 保留不动（既有测试不破坏）。
   - 回归测试：`model/OpenAiCompatibleProviderRetryTest` 新增两个用例
     （抖动落在半退避到全退避之间；多次采样确实发散）。

**工程化加固：**
- 依赖：保持 Spring Boot 3.3.5 父 POM 不跨大版本；`spring-boot-starter-actuator` 已在 pom 中，
  `application.yml` 仅对外暴露 `health,info,metrics`（不暴露全量端点）。
- 补边界/故障注入单测：`model/FaultInjectionRoutingTest`（可编程假提供方），
  验证"断熔 OPEN 期间不再触碰已熔断提供方""全部提供方故障时抛业务异常且记账"。

**新增功能：模型响应幂等缓存（默认关闭，opt-in）。**
- 位置：新增 `model/ModelResponseCache`，由 `ModelRouter` 持有。
- 行为：对相同 `(taskType, systemPrompt, messages)` 的请求，在 TTL 内直接返回上次成功结果，
  不再经过断熔/降级链；只缓存成功响应（失败结果无复用价值）；键为内容 SHA-256，不存大 prompt。
- 配置：`hive.model.cache.enabled`（默认 `false`，避免改变既有路由/断熔语义）、
  `ttl-seconds`（默认 60）、`max-entries`（默认 256，按插入序淘汰最旧）。
- 可注入时钟（`LongSupplier`），单测无需 sleep 即可验证 TTL 过期。
- 诊断：`ModelRouter.cacheStats()` 返回 `enabled/hits/misses/size`（不含任何 prompt 内容）。
- 回归测试：`model/ModelResponseCacheTest`（命中不重算、不同消息不串、TTL 过期失效、有界淘汰、关闭时不生效、路由层不重复调用提供方）。

**验证命令与结果：**

```
mvn -B -o test
# Tests run: 85, Failures: 0, Errors: 0, Skipped: 0 —— BUILD SUCCESS（基线 73 → 85）
```

## 11. License

MIT © 2026 杨锴
