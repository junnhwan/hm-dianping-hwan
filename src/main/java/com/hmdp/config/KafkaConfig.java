package com.hmdp.config;

import com.hmdp.mq.KafkaTopics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic seckillOrderTopic() {
        return TopicBuilder.name(KafkaTopics.SECKILL_ORDER)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic cacheEvictTopic() {
        return TopicBuilder.name(KafkaTopics.CACHE_EVICT)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
