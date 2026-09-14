package com.ratelimiter.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import static org.junit.jupiter.api.Assertions.assertTrue;




import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class TokenBucketLimiterTest {

    @Autowired
    private TokenBucketLimiter limiter;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    private static final String CLIENT_ID = "race-test-client";

    @BeforeEach
    void cleanBucket() {
        redisTemplate.delete("ratelimit:" + CLIENT_ID);
    }

    @Test
    void concurrentRequests_onlyAllowsExactlyCapacity() throws Exception {

        int threads = 50;

        ExecutorService executor =
                Executors.newFixedThreadPool(threads);

        AtomicInteger allowedCount =
                new AtomicInteger(0);

        CountDownLatch ready =
                new CountDownLatch(threads);

        CountDownLatch start =
                new CountDownLatch(1);

        CountDownLatch finished =
                new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {

            executor.submit(() -> {
                ready.countDown();

                try {
                    // Make all threads wait here.
                    start.await();

                    if (limiter.isAllowed(CLIENT_ID)) {
                        allowedCount.incrementAndGet();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                } finally {
                    finished.countDown();
                }
            });
        }

        // Make sure all 50 threads are ready.
        
        assertTrue(ready.await(5, TimeUnit.SECONDS));

        // Release all threads at approximately the same time.
        start.countDown();

        // Wait for all requests to finish.
        assertTrue(finished.await(10, TimeUnit.SECONDS));

        executor.shutdown();

        assertEquals(10, allowedCount.get());
    }
}