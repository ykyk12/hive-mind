package com.hivemind;

import com.hivemind.config.HiveProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * HiveMind 节点入口。
 *
 * 这个 jar 既是"大脑"也是"神经元"：启动时一律以 NEURON 身份加入集群，
 * 心跳/租约超时后按 term 竞选 BRAIN，因此不需要为不同角色打不同的包。
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(HiveProperties.class)
public class HiveMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(HiveMindApplication.class, args);
    }
}
