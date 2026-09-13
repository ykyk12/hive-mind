package com.hivemind.govern;

import com.hivemind.config.HiveProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 治理接口的入口门锁：改代码/改配置的接口必须带管理密钥。
 * 比较用 MessageDigest.isEqual 做定长时间比较，避免把密钥比较变成可测的时序侧信道。
 */
@Component
@RequiredArgsConstructor
public class GovernanceInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Hive-Admin-Key";

    private final HiveProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws java.io.IOException {
        String provided = request.getHeader(HEADER);
        String expected = properties.getGovernance().getAdminKey();
        if (expected == null || expected.isBlank() || provided == null || !matches(provided, expected)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\","
                    + "\"message\":\"治理与进化接口需要 " + HEADER + " 请求头\"}");
            return false;
        }
        return true;
    }

    private boolean matches(String provided, String expected) {
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
