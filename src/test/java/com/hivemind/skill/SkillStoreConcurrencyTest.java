package com.hivemind.skill;

import com.hivemind.agent.SkillAdvisor;
import com.hivemind.config.HiveProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：SkillStore 的 versions 内层是普通 ArrayList，publish/recordUsage 会原地增删改。
 * 修复前 active()/versions()/allActive()/hintsFor() 未与写方法同锁，并发写入时
 * 读线程会抛 ConcurrentModificationException 或读到撕裂状态。修复后读写互斥。
 */
class SkillStoreConcurrencyTest {

    @Test
    void 并发发布与召回不抛并发修改异常() throws Exception {
        HiveProperties properties = new HiveProperties();
        SkillStore store = new SkillStore(properties);

        int writers = 3;
        int readers = 4;
        int iterations = 300;
        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();

        // 先有一条基线数据，让召回/读取有对象
        store.publish(artifact("shared", 0, "订单超时处理", "先查物流再回复"));

        for (int w = 0; w < writers; w++) {
            final String skillId = "skill-" + w;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        store.publish(artifact(skillId, 0, "标题" + i, "做法" + i));
                        store.recordUsage(new SkillUsage(skillId, 1, "node-a", "t" + i, true));
                        store.upsertFromPeer(artifact(skillId, 1, "远端", "远端做法"), "node-b");
                    }
                } catch (Throwable t) {
                    failures.incrementAndGet();
                }
            });
        }
        for (int r = 0; r < readers; r++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterations; i++) {
                        store.active("shared");
                        store.versions("shared");
                        List<SkillArtifact> all = store.allActive();
                        List<SkillAdvisor.SkillHint> hints = store.hintsFor("订单超时怎么办", 5);
                        // 读回来的集合必须自洽：不能比空集合还少
                        if (all == null || hints == null) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Throwable t) {
                    // 修复前这里会因并发写 ArrayList 而抛 ConcurrentModificationException
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "并发任务应在超时内结束");
        assertEquals(0, failures.get(), "并发读写期间不允许抛 ConcurrentModificationException 或任何异常");
    }

    @Test
    void 读取快照与写入互斥_版本号单调可读() throws Exception {
        HiveProperties properties = new HiveProperties();
        SkillStore store = new SkillStore(properties);
        store.publish(artifact("monotonic", 0, "t", "p"));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger failures = new AtomicInteger();
        for (int t = 0; t < 4; t++) {
            final boolean writer = (t % 2 == 0);
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < 200; i++) {
                        if (writer) {
                            store.publish(artifact("monotonic", 0, "t" + i, "p" + i));
                        } else {
                            List<SkillArtifact> versions = store.versions("monotonic");
                            // List.copyOf 不可变：每一项都必须能取到 version
                            for (SkillArtifact a : versions) {
                                if (a.version() <= 0) {
                                    failures.incrementAndGet();
                                }
                            }
                        }
                    }
                } catch (Throwable ex) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
    }

    private SkillArtifact artifact(String skillId, int version, String title, String procedure) {
        return new SkillArtifact(skillId, version, title, title, procedure,
                Set.of("test"), Set.of("node-a"), "node-a", System.currentTimeMillis());
    }
}
