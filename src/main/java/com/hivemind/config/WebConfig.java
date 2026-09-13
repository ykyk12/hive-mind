package com.hivemind.config;

import com.hivemind.cluster.ClusterAuthInterceptor;
import com.hivemind.govern.GovernanceInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 两道门：
 *  - 治理/进化接口（能改写系统自身）必须带管理密钥；
 *  - 节点间接口在配置了 cluster-token 之后必须带节点密钥。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final GovernanceInterceptor governanceInterceptor;
    private final ClusterAuthInterceptor clusterAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(governanceInterceptor)
                .addPathPatterns("/api/v1/governance/**", "/api/v1/evolution/**");
        registry.addInterceptor(clusterAuthInterceptor)
                .addPathPatterns("/api/v1/cluster/**");
    }
}
