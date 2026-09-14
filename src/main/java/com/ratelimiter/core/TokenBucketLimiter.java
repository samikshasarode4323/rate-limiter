package com.ratelimiter.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Component
public class TokenBucketLimiter {

    private static final Logger log =
            LoggerFactory.getLogger(TokenBucketLimiter.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final RedisScript<List> script;

    private static final int CAPACITY = 10;
    private static final double REFILL_RATE = 1.0;

    public TokenBucketLimiter(
            RedisTemplate<String, String> redisTemplate,
            RedisScript<List> script) {
        this.redisTemplate = redisTemplate;
        this.script = script;
    }

    public boolean isAllowed(String clientId) {
        String key = "ratelimit:" + clientId;
        long now = Instant.now().getEpochSecond();

        try {
            List<Long> result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    String.valueOf(CAPACITY),
                    String.valueOf(REFILL_RATE),
                    String.valueOf(now)
            );

            return result.get(0) == 1;

        } catch (RedisConnectionFailureException e) {
            log.warn(
                    "Redis unavailable, failing open for client {}: {}",
                    clientId,
                    e.getMessage()
            );
            return true;

        } catch (Exception e) {
            log.error(
                    "Unexpected error checking rate limit for client {}",
                    clientId,
                    e
            );
            return true;
        }
    }
}