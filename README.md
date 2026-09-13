# HiveMind —— 同构蜂巢智能体平台

> 一句话：**同一份代码，既是"大脑"也是"神经元"；能调用多家模型各取所长；能把经验蒸馏成可版本化的技能并在节点间传播；能改写自己的插件与配置，但改写必须过"提议者—审核者—门锁"三权分立，且永远可回滚。**

技术栈：Java 17 · Spring Boot 3.3 · 纯 JDK HttpClient（不引任何 LLM SDK）· Micrometer/Actuator · springdoc-openapi · Docker Compose · GitHub Actions

当前版本 **1.0.0（M0 内核 + M1 集群）**：单 jar 多进程可跑通，无需数据库、无需外部 API（内置本地确定性模型）。

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

规则与判据的完整说明见 [`docs/GOVERNANCE.md`](docs/GOVERNANCE.md)。

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
| 测试门禁 | 目前是"编译 + 加载 + 冒烟调用" | M3 用 JUnit Platform Launcher 在隔离类加载器里跑候选真实单测 |
| 内核自改 | **不支持自动应用**（故意） | 保持人工评审 + 发布流程 |
| 节点间认证 | 无 token 时放行（仅本机演示） | 生产必须配 `HIVE_CLUSTER_TOKEN`，并考虑双向 TLS |
| 通用 HTTP 工具 | 默认只允许本机主机白名单 | 按租户配置出网白名单与配额 |

## 9. 路线图

- **M2 经验面完善**：技能 A/B 影子对比、适应度驱动的版本淘汰、召回质量评估集
- **M3 测试门禁**：在隔离类加载器中运行候选插件的真实单元测试，淘汰标准量化
- **M4 工具面**：MQTT 智能家居直连、SSH 执行器（HIGH 风险 + 白名单 + 审批）、浏览器读写工具
- **后端工业化**：持久化存储、指标看板、限流与配额、灰度发布节点级金丝雀

## 10. 版本记录

| 版本 | 说明 |
|---|---|
| 1.0.0 | 首个功能提交（M0 内核 + M1 集群）：多模型路由与断熔、Agent 循环与风险门、技能蒸馏与反熵传播、三权分立门禁与隔离进化、心跳/选举/任务下发、测试与 CI |

## 11. License

MIT © 2026 杨锴
