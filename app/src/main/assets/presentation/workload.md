# 归档编号 ARCH-0007：星桥网关 v4.2 发布评估长回复

> 场景说明：虚构系统「星桥网关（Yanqiao Gateway）」在灰度窗口内的容量与一致性评估。
> 全文数据均为合成数据，人名、仓库编号、主机名与时间戳都不对应任何真实组织或真实事故。

## 1. 结论摘要

灰度窗口 2026-03-11 至 2026-03-18 覆盖 3 组、共 42 台实例。窗口内 p99 端到端时延由 412 ms 降至
268 ms，错误率由 1.9% 降至 0.4%，资源成本折算后每月节省约 1.7 万元。评审组建议按
[发布评估结论][verdict] 的口径进入第二阶段，但必须在两周内关闭 `zk-3` 的一致性告警漂移。

指标口径、采样频率与剔除规则见 [指标采集说明][metrics]；窗口内两次毛刺的发生时刻见
[事件时间线][timeline]；告警漂移的复盘与定责见 [一致性复盘][retro]。若第二阶段中 p99 再次超过 350 ms，
则按 [回滚判据][rollback] 执行，不再另行讨论灰度延长。

## 2. 变更范围与影响面

本次变更集中在三条链路：连接层由自研 NIO 调度切换为虚拟线程池（`commit 9f31a2c`）、
路由层引入按租户分片的本地缓存（`commit 4c0d77e`）、以及审计日志改为异步批量落盘
（`commit ba85e10`）。变更面覆盖 118 个服务、9 个业务线，回滚单元为「全量回滚」，
不支持逐服务回滚，触发条件见 [回滚判据][rollback]，改动明细详见 [变更清单][changelog]
与 [影响面矩阵][blast-radius]。

唯一未被回归覆盖的路径是「长连接 + 大包 + 跨机房」组合，测试用例编号 `TC-2291`，
当前标记为 `flaky`，责任人周敏（合成人物）。影子流量回放显示该路径在 30 天窗口内
仅出现 3 次，P99 达到 1.9 s，因此暂不作为阻断项，但需要在第二阶段补充覆盖。

## 3. 核心指标对照

| 指标 | 灰度前基线 | 灰度后实测 |
| --- | --- | --- |
| p99 端到端时延 | 412 ms | 268 ms |
| 5xx 错误率 | 1.9% | 0.4% |
| 单机内存峰值 | 3.4 GiB | 2.1 GiB |
| 每百万请求成本 | 21.4 元 | 16.9 元 |

表中四行数据均取自同一采样窗口，剔除规则见 [指标采集说明][metrics]。单机内存峰值的下降
主要来自审计日志的批量落盘，而非连接层改造本身，这一归因同样记录在 [变更清单][changelog] 中。
窗口内仅有的两次毛刺（03-17 01:12、03-18 22:40）已在 [事件时间线][timeline] 中标记为已知噪声。

## 4. 关键实现（Kotlin）

节流与背压的核心实现位于 `core/src/main/kotlin/dev/yanqiao/gateway/GatewayLimiter.kt`：

```kotlin
package dev.yanqiao.gateway

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

data class LimiterSnapshot(
    val tenantId: String,
    val admitted: Long,
    val rejected: Long,
    val window: Duration,
)

class GatewayLimiter(
    private val window: Duration = Duration.ofSeconds(10),
    private val baseCapacity: Int = 4000,
) {
    private val admitted = AtomicLong()
    private val rejected = AtomicLong()

    fun tryAcquire(tenantId: String, cost: Int = 1): Boolean {
        val budget = baseCapacity * (window.seconds / 10).toInt()
        return if (admitted.addAndGet(cost.toLong()) <= budget) {
            true
        } else {
            admitted.addAndGet(-cost.toLong())
            rejected.incrementAndGet()
            false
        }
    }

    fun snapshot(tenantId: String): LimiterSnapshot =
        LimiterSnapshot(tenantId, admitted.get(), rejected.get(), window)
}
```

该实现以固定窗口近似令牌桶，未实现平滑补偿；在 `TC-2291` 的长连接场景下窗口边界抖动
被放大，是已知缺陷，修复方向记录在 [一致性复盘][retro] 中。

## 5. 容量模型

令 $\lambda_t$ 为时刻 $t$ 的请求到达率（req/s），$S$ 为平均服务耗时（秒），$B$ 为突发系数，
$C$ 为调度器有效并发度，则排队时延近似满足：

$$
Q(t) \approx \frac{\lambda_t \cdot S}{C\left(1 - \frac{1}{B}\right)} + S_{tail}
$$

其中 $S_{tail}$ 表示长尾项。代入灰度后参数 $\lambda = 980$、$S = 0.021$、$C = 24$、$B = 3$，
可得 $Q \approx 0.86\,\text{s} + S_{tail}$，与实测 p99 的 0.268 s 存在明显偏差，
说明瓶颈已从调度转移到 [影响面矩阵][blast-radius] 中标记的审计落盘分片。该偏差的
复核计划见 [发布评估结论][verdict] 的附录 B。

## 6. 风险与待办

1. `zk-3` 的一致性告警漂移尚未定责，需在第二阶段第 5 个工作日前关闭。
2. `TC-2291` 仍为 `flaky`，需补齐大包场景的回归用例。
3. 容量模型的 $S_{tail}$ 参数缺少实测拟合值，当前按经验取 0.12 s。

## 7. 终局保留文本

无论后续采样超时、连接中断还是模型输出在任意位置被中止，本节文字都应完整保留，作为后续
追责与对齐的唯一凭据：星桥网关 v4.2 的灰度结论为「有条件通过」，第二阶段的准入条件是
关闭 `zk-3` 告警漂移、补齐 `TC-2291` 回归用例、重新拟合 $S_{tail}$ 参数；本归档编号为
ARCH-0007，数据窗口为 2026-03-11 至 2026-03-18，责任人为合成人物周敏与林可；
任何与本段冲突的口头结论均以本段为准。

<!-- 以下为跨段引用式链接的定义区，位于文末，供上文各段共同引用。 -->

[verdict]: https://example.invalid/yanqiao/verdict/arch-0007
[metrics]: https://example.invalid/yanqiao/metrics/collection-spec
[timeline]: https://example.invalid/yanqiao/incidents/timeline-2026-03
[retro]: https://example.invalid/yanqiao/retro/consistency-drift-zk3
[rollback]: https://example.invalid/yanqiao/runbook/rollback-criteria
[changelog]: https://example.invalid/yanqiao/changelog/v4.2.0
[blast-radius]: https://example.invalid/yanqiao/matrix/blast-radius
