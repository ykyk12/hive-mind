package com.hivemind.evolve;

import java.util.List;

/** 应用结果：applied=false 时必须给出人能看懂的原因（这是"被门禁拦下"的正常输出，不是异常）。 */
public record ApplyResult(String proposalId,
                          boolean applied,
                          String artifactRef,
                          String message,
                          List<String> steps,
                          long elapsedMillis) {
}
