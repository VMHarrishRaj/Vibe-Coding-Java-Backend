package com.truckhire.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration.
 *
 * We use a plain String→String RedisTemplate. Every key and value we store
 * is text (JWT JTI claims, user IDs, JSON payloads), so there's no need
 * for object serialization. StringRedisSerializer keeps Redis keys human-readable
 * — useful when inspecting the store with `redis-cli KEYS *`.
 *
 * Connection settings come from application-{profile}.yml:
 *   local/dev: localhost:6379 (Docker)
 *   prod:      ${REDIS_HOST}:${REDIS_PORT} with ${REDIS_PASSWORD}
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
