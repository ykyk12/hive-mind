package com.hivemind.change;

import java.util.List;

/** 审核结论。blockers 非空即否决；riskScore 交给门锁做阈值判断。 */
public record ReviewVerdict(String proposalId,
                            String reviewer,
                            boolean approved,
                            int riskScore,
                            List<String> blockers,
                            List<String> warnings,
                            String summary,
                            String advisory,
                            long reviewedAtMillis) {
}
