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
        // Ограничитель один на аккаунт, а не на поток: раньше у действий был
        // свой счётчик, и два потока проходили пачку вдвое быстрее. Поэтому
        // одни и те же пятьдесят обращений в одном потоке и в двух должны
        // тормозить одинаково, а не ровно пополам
        int calls = 50;

        Throttle single = new Throttle();
        Instant oneStart = Instant.now();
        for (int i = 0; i < calls; i++) {
            single.allow();
        }
        long alone = Duration.between(oneStart, Instant.now()).toMillis();
        assertTrue(alone >= 1900, "пачка из " + calls + " обращений прошла без пауз");

        Throttle shared = new Throttle();
        int threads = 2;
        int perThread = calls / threads;
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        Instant bothStart = Instant.now();
        for (int t = 0; t < threads; t++) {
            Thread worker = Thread.ofPlatform().daemon().start(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    shared.allow();
                }
            });
            workers.add(worker);
        }
        go.countDown();
        for (Thread worker : workers) {
            worker.join(Duration.ofSeconds(25));
        }
        long together = Duration.between(bothStart, Instant.now()).toMillis();

        assertTrue(together * 4 >= alone * 3,
                "два потока прошли пачку быстрее одного: бюджет не сквозной ("
                        + alone + " мс против " + together + " мс)");
    }
}
