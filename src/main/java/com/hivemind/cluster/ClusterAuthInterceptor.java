package com.hivemind.cluster;

import com.hivemind.config.HiveProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 节点间接口的密钥校验。
 *
 * 未配置 hive.node.cluster-token 时放行（本机多进程演示的默认形态），
 * 一旦配置了就必须带 X-Hive-Cluster-Token——否则任何人伪造一个 /vote 就能把集群搅乱。
 * 这条边界在 README 里明确写出：单机 demo 不校验 ≠ 生产可以裸奔。
 */
@Component
@RequiredArgsConstructor
public class ClusterAuthInterceptor implements HandlerInterceptor {

    public static final String HEADER = "X-Hive-Cluster-Token";

    private final HiveProperties properties;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String expected = properties.getNode().getClusterToken();
        if (expected == null || expected.isBlank()) {
            return true;
        }
        String provided = request.getHeader(HEADER);
        if (provided != null && MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\","
                + "\"message\":\"节点间接口需要 " + HEADER + " 请求头\"}");
        return false;
    }
}
