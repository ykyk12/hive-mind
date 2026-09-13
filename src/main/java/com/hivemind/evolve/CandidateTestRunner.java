package com.hivemind.evolve;

import com.hivemind.config.HiveProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 候选测试执行器（M3 真实测试门禁的核心）。
 *
 * 与"冒烟"的区别：
 *  - 冒烟只证明"能加载、能被调用"；
 *  - 这里在**隔离类加载器**里用 JUnit Platform Launcher 跑候选插件自带的真实单元测试，
 *    断言逻辑、边界条件、异常路径都由候选自己写出来后接受检验。
 *
 * 三个工程细节：
 * 1) 通过线程上下文类加载器（TCCL）让 Launcher 从隔离加载器发现候选类；
 * 2) 带超时执行：候选测试可能死循环，门禁不能被它拖死；
 * 3) "测试框架不可用"与"测试失败"必须区分开：前者是环境问题（拒绝并要求修环境），
 *    绝不能当成"通过"——门禁在不确定时必须偏向拒绝。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateTestRunner {

    private final HiveProperties properties;

    public record TestReport(boolean available,
                             boolean success,
                             int found,
                             int succeeded,
                             int failed,
                             List<String> failures,
                             String message) {

        static TestReport unavailable(String message) {
            return new TestReport(false, false, 0, 0, 0, List.of(), message);
        }
    }

    public TestReport run(Path classesDir, String testClassName) {
        if (!junitAvailable()) {
            return TestReport.unavailable("测试门禁不可用：类路径缺少 junit-platform-launcher / junit-jupiter-engine");
        }
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "hive-candidate-test");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<TestReport> future = executor.submit(() -> execute(classesDir, testClassName));
            return future.get(properties.getEvolve().getTestTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return new TestReport(false, false, 0, 0, 0, List.of(),
                    "候选测试超时（>" + properties.getEvolve().getTestTimeoutSeconds() + " 秒）：按失败处理");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TestReport.unavailable("候选测试被中断");
        } catch (ExecutionException e) {
            return new TestReport(false, false, 0, 0, 0, List.of(),
                    "候选测试执行异常：" + e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    private TestReport execute(Path classesDir, String testClassName) throws Exception {
        try (IsolatedToolClassLoader loader = new IsolatedToolClassLoader(classesDir,
                CandidateTestRunner.class.getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                        .selectors(DiscoverySelectors.selectClass(testClassName))
                        .build();
                Launcher launcher = LauncherFactory.create();
                SummaryGeneratingListener listener = new SummaryGeneratingListener();
                launcher.execute(request, listener);
                TestExecutionSummary summary = listener.getSummary();

                List<String> failures = new ArrayList<>();
                for (TestExecutionSummary.Failure failure : summary.getFailures()) {
                    failures.add(failure.getTestIdentifier().getDisplayName() + " → " + failure.getException());
                }
                int found = (int) summary.getTestsFoundCount();
                int succeeded = (int) summary.getTestsSucceededCount();
                int failed = (int) summary.getTestsFailedCount();

                if (found == 0) {
                    return new TestReport(true, false, 0, 0, 0, List.of(),
                            "测试门禁要求候选自带可执行的单元测试，但一个都没发现（testClassName=" + testClassName + "）");
                }
                boolean success = failed == 0 && summary.getTotalFailureCount() == 0;
                String message = success
                        ? "候选单元测试全部通过（" + succeeded + "/" + found + "）"
                        : "候选单元测试未通过（成功 " + succeeded + "，失败 " + failed + "）";
                log.info("候选测试结果：{}（testClass={}，discovery 失败 {} 个）",
                        message, testClassName, summary.getContainersFailedCount());
                return new TestReport(true, success, found, succeeded, failed, List.copyOf(failures), message);
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private boolean junitAvailable() {
        try {
            Class.forName("org.junit.platform.launcher.core.LauncherFactory", false,
                    CandidateTestRunner.class.getClassLoader());
            Class.forName("org.junit.jupiter.engine.JupiterTestEngine", false,
                    CandidateTestRunner.class.getClassLoader());
            return true;
        } catch (Throwable e) {
            log.warn("JUnit 运行时不完整，测试门禁将拒绝一切插件变更：{}", e.toString());
            return false;
        }
    }
}
