# 自改治理：三权分立与门锁

这套机制解决一个问题：**一个能改自己代码的系统，怎么保证"改到自己不能跑"之前被拦住。**

答案是：不允许任何单一角色把变更推进到生效。提议、审核、放行、签名四件事分别由不同角色做，其中"放行"这一环是最严格的**纯确定性**策略。

## 1. 四个角色

| 角色 | 由谁承担 | 能做什么 | 不能做什么 |
|---|---|---|---|
| 提议者 Proposer | 模型（`proposer-agent`） | 产出结构化变更 + 收益声明 | 不能审自己、不能放行 |
| 审核者 Reviewer | 规则静态分析（`reviewer-agent`） | 判定能不能编译/会不会伤到自己，给出风险分 | 结论不含"放行"权 |
| 门锁 Governor | 纯策略代码（`governor`） | 否决、或升级为"需人工签名" | 不采信模型意见 |
| 签名人 Signer | 人（例如 `human-ops-1`） | 对被升级的变更负责 | 不能是提议者或审核者 |

强制的分权点（违反直接 409）：

- `governance.review`：审核者 == 提议者 → 拒绝（自审禁令）。
- `governance.sign`：签名人 == 提议者或审核者 → 拒绝。
- `governance.apply`：没有裁决、裁决未放行、或需要人工签名却没签名 → 拒绝。

## 2. 门锁规则表

| 规则 | 判据 | 结果 |
|---|---|---|
| 缺审核结论 | `verdict == null` | 否决 |
| 审核否决 | `verdict.approved == false` | 否决 |
| 自审 | 审核者 == 提议者 | 否决 |
| 无收益声明 | `expectedBenefit` 为空且 `direction.require-benefit-statement=true` | 否决 |
| 反方向进化 | `costIncreaseRatio > 0.30` 且 `successGain <= 0` | 否决 |
| 类型不在自动放行清单 | `kind ∉ {SKILL, CONFIG, PLUGIN}`（`hive.evolve.auto-apply-kinds`） | 升级人工 |
| 内核变更 | `kind == KERNEL` 且 `kernel-requires-human=true` | 升级人工 |
| 风险分过高 | `verdict.riskScore > governance.max-risk-score` | 升级人工 |
| 自报风险过高 | `declaredRisk > direction.max-declared-risk` | 升级人工 |
| 全局要求签名 | `human-signature-required=true` | 升级人工 |

## 3. 审核者的确定性检查

| 检查 | 说明 |
|---|---|
| 禁止模式 | 正则黑名单：`System.exit`、`Runtime.exec`、`ProcessBuilder`、反射、`setAccessible(true)`、`Unsafe`、`Files.delete`、`System.setProperty` 等 |
| 依赖面 | import 只允许 `java.*` 与 `com.hivemind.agent.*`（隔离编译环境只提供这些） |
| 契约 | 必须 `implements Tool`，且源码中存在 `public class <className>` |
| 包名 | 必须以 `com.hivemind.plugins` 开头 |
| 体量 | 行数 ≤ `max-plugin-source-lines`（默认 400） |
| 配置键 | 必须在 `ConfigWhitelist`（`agent.max-steps`、`agent.max-injected-skills`、`skill.promotion-min-nodes`、`governance.max-risk-score`） |

风险分从 10 起算：插件 +15、内核 +45、源码超六成额度 +10、含并发/网络能力 +10、自报高风险 +10，上限 100。

> 说明：审核结论里带一句"顾问意见"，明确写出**模型不参与判定**——避免以后有人误以为可以让模型来放行。

## 4. 应用阶段（真正改系统的动作）

```
落盘 staging → javax.tools 隔离编译 → 子优先 ClassLoader 加载 → 冒烟调用
      → 制品归档 hive/artifacts/{name}/v{n} → 注册表指针切换 → 审计留痕
```

- 任一环节失败：候选代码就地丢弃，运行中的实例不受影响，原因写入审计。
- `KERNEL` 类型即使签名通过也**不会**被自动应用：返回 `applied=false` 并提示走人工评审与发布流程。
- 冒烟是"能加载 + 能实例化 + 能按参数契约被调用并返回非空结果"，**不等于**完整单元测试（见 README 路线图 M3）。

## 5. 回滚

| 类型 | 回滚语义 |
|---|---|
| PLUGIN | 注册表指针退回上一制品版本（要求确实存在更早版本） |
| CONFIG | 恢复热改前的旧值（`ConfigOverlay` 记录） |
| SKILL | 制品指针回退（技能库按版本保留） |

```bash
curl -H "X-Hive-Admin-Key: hive-admin-key" -X POST \
  "http://localhost:8101/api/v1/evolution/rollback?name=text_stats"
```

## 6. 完整演示序列

```bash
KEY="X-Hive-Admin-Key: hive-admin-key"
BASE=http://localhost:8101

# 生成提案
PID=$(curl -s -H "$KEY" -H "Content-Type: application/json" \
  -d '{"goal":"补一个零依赖的文本统计工具"}' \
  $BASE/api/v1/governance/proposals/generate | sed -n 's/.*"proposalId":"\([^"]*\)".*/\1/p')
echo "proposal=$PID"

# 自审 → 必须被拒
curl -s -H "$KEY" -H "Content-Type: application/json" -d '{"reviewer":"proposer-agent"}' \
  $BASE/api/v1/governance/proposals/$PID/review

# 换审核者 → 通过
curl -s -H "$KEY" -H "Content-Type: application/json" -d '{"reviewer":"reviewer-agent"}' \
  $BASE/api/v1/governance/proposals/$PID/review

# 门锁裁决
curl -s -H "$KEY" -X POST $BASE/api/v1/governance/proposals/$PID/gate

# 应用（隔离编译 + 冒烟 + 注册）
curl -s -H "$KEY" -X POST $BASE/api/v1/governance/proposals/$PID/apply

# 用新工具
curl -s -H "Content-Type: application/json" \
  -d '{"input":"CALL_TOOL:text_stats {\"text\":\"hive mind\"}"}' $BASE/api/v1/tasks

# 审计留痕与回滚
curl -s -H "$KEY" "$BASE/api/v1/governance/audit?proposalId=$PID"
curl -s -H "$KEY" -X POST "$BASE/api/v1/evolution/rollback?name=text_stats"
```

**危险插件演示**：把 `System.exit(1)` 放进插件源码，会在**审核阶段**就被否决，连编译机会都没有——因为审核发生在应用之前。

## 7. 当前不覆盖的部分（明确列出，避免误解）

- 策略配置本身没有走同一套门禁（改 `application.yml` 需要重启，属于人工运维动作）；
- 审计轨迹在内存，重启丢失；
- 没有做"多节点同时自改"的并发互斥（当前假设治理 API 只在单个大脑节点被调用）；
- 没有对插件做资源限制（CPU/内存/超时墙），隔离仅限于类加载与静态检查。
