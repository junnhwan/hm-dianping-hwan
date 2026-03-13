package com.hmdp.mq;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class CacheEvictProducer {

    @Resource
    private KafkaTemplate<String, CacheEvictMessage> kafkaTemplate;

    public void sendEvict(String key, int retry) {
        CacheEvictMessage message = new CacheEvictMessage(key, retry, System.currentTimeMillis());
        kafkaTemplate.send(KafkaTopics.CACHE_EVICT, key, message);
    }
}
