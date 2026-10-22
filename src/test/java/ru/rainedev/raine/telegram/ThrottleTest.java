package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ThrottleTest {

    @Test
    @Timeout(10)
    void aFewCallsGoThroughWithoutWaiting() {
        Throttle throttle = new Throttle();
        Instant start = Instant.now();

        for (int i = 0; i < 15; i++) {
            throttle.allow();
        }

        assertTrue(Duration.between(start, Instant.now()).toMillis() < 500, "редкие обращения задерживать незачем");
    }

    @Test
    @Timeout(20)
    void aBurstIsSlowedDown() {
        // Telegram блокирует аккаунты за слишком частые обращения сторонних клиентов
        Throttle throttle = new Throttle();
        Instant start = Instant.now();

        for (int i = 0; i < 45; i++) {
            throttle.allow();
        }

        assertTrue(Duration.between(start, Instant.now()).toMillis() >= 900,
                "поток запросов обязан притормаживаться");
    }

    @Test
    @Timeout(30)
    void burstIsSharedBetweenThreads() throws Exception {
        // два потока решают, что последний свободный слот их, — и обращений
        // уходит больше, чем Telegram прощает. Считаем окна целиком: ни в одну
        // секунду через ограничитель не должно пройти больше двадцати запросов
        Throttle throttle = new Throttle();
        int threads = 2;
        int perThread = 25;
        long[][] passed = new long[threads][perThread];
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int lane = t;
            Thread worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    throttle.allow();
                    passed[lane][i] = System.nanoTime();
                }
            });
            workers.add(worker);
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(java.time.Duration.ofSeconds(25));
        }

        List<Long> all = new ArrayList<>();
        for (long[] lane : passed) {
            for (long at : lane) {
                all.add(at);
            }
        }
        all.sort(Long::compare);
        int ceiling = 20;
        for (int i = 0; i < all.size(); i++) {
            int inSecond = 0;
            for (long at : all) {
                if (at - all.get(i) < 1_000_000_000L) {
                    inSecond++;
                }
            }
            assertTrue(inSecond <= ceiling,
                    "за секунду прошло " + inSecond + " обращений — лимит не сквозной");
        }
    }
}
