package com.yupi.springbootinit.airuntime.config;

import com.yupi.springbootinit.airuntime.api.AiChatClient;
import com.yupi.springbootinit.airuntime.provider.zhipu.ZhipuAiChatClient;
import com.yupi.springbootinit.airuntime.resilience.ResilientAiChatClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(AiRuntimeProperties.class)
public class AiRuntimeConfiguration {

    /** 业务模块默认注入带指数退避与备用模型降级的弹性客户端。 */
    @Bean
    @Primary
    public AiChatClient resilientAiChatClient(ZhipuAiChatClient zhipuAiChatClient,
                                              AiRuntimeProperties properties) {
        return new ResilientAiChatClient(zhipuAiChatClient, properties);
    }
}
