package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.LlmFailure;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.llm.ToolCall;

/**
 * Пропавшая сеть стоила ей целого сообщения: клиент отрабатывал три попытки,
 * дальше уведомление вылетало с ошибкой и никто к нему не возвращался.
 * Найдено в логах сервера за 31.07.
 * <p>
 * 22.10 то же самое провернулось с занятой моделью: пять пауз по минуте
 * кончились через двенадцать минут, а эндпоинт ожил девятью минутами позже —
 * сообщение владельца осталось прочитанным и без ответа.
 */
class LostNotificationTest {

    /** Первые несколько раз изображает обрыв сети, потом отвечает как обычно. */
    private static final class FlakyNetwork implements LlmClient {
        private final int failures;
        private final Supplier<RuntimeException> stumble;
        private int calls;
        private List<Message> lastContext = List.of();

        FlakyNetwork(int failures) {
            this(failures, () -> new UncheckedIOException("Сеть недоступна", new IOException("connect")));
        }

        /** Сбой другого рода — например занятая модель или отвергнутый запрос. */
        FlakyNetwork(int failures, Supplier<RuntimeException> stumble) {
            this.failures = failures;
            this.stumble = stumble;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            calls++;
            if (calls <= failures) {
                throw stumble.get();
            }
            lastContext = List.copyOf(history);
            Message message = new Message(Message.Role.ASSISTANT, "",
                    List.of(new ToolCall("call_wait", "function", new ToolCall.Function("wait", "{}"))), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                    new ChatResponse.Usage(10, 0, 10));
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }

        int calls() {
            return calls;
        }

        /** Сколько раз в контексте стоит реплика из уведомления — важнее одного быть не должно. */
        long copiesOf(String text) {
            return lastContext.stream()
                    .filter(m -> m.content() != null && m.content().contains(text))
                    .count();
        }
    }

    private static Thread start(NotificationLoop loop) {
        Thread thread = Thread.ofVirtual().start(loop::run);
        return thread;
    }

    @Test
    void notificationSurvivesNetworkOutage() throws Exception {
        FlakyNetwork llm = new FlakyNetwork(2);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(20));

        CountDownLatch done = new CountDownLatch(1);
        loop.onNotificationDone(notification -> done.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет"));

        assertTrue(done.await(5, TimeUnit.SECONDS), "уведомление так и не было обработано");
        worker.interrupt();

        assertEquals(3, llm.calls(), "две попытки должны были упереться в сеть, третья — пройти");
        assertEquals(1, llm.copiesOf("Серёжа написал: привет"),
                "после неудачной попытки сообщение не должно оставаться в контексте дважды");
    }

    @Test
    void retryWaitsLongerEveryTime() {
        // занятая модель оттаивает десятками минут; пять пауз по минуте
        // заканчивались раньше, чем она успевала освободиться
        Duration base = Duration.ofSeconds(60);
        assertEquals(Duration.ofSeconds(60), NotificationLoop.pauseBeforeAttempt(base, 1));
        assertEquals(Duration.ofSeconds(120), NotificationLoop.pauseBeforeAttempt(base, 2));
        assertEquals(Duration.ofSeconds(240), NotificationLoop.pauseBeforeAttempt(base, 3));
        assertEquals(Duration.ofSeconds(480), NotificationLoop.pauseBeforeAttempt(base, 4));
        assertEquals(Duration.ofMinutes(10), NotificationLoop.pauseBeforeAttempt(base, 5));
        assertEquals(Duration.ofMinutes(10), NotificationLoop.pauseBeforeAttempt(base, 6));
        long patience = java.util.stream.IntStream.rangeClosed(1, 5)
                .mapToObj(attempt -> NotificationLoop.pauseBeforeAttempt(base, attempt))
                .mapToLong(Duration::toSeconds)
                .sum();
        assertTrue(patience >= 1500, "суммарного терпения не хватает: " + patience + " с");
    }

    @Test
    void strandedNotificationComesBackWhenTheModelRecovers() throws Exception {
        // шесть отказов подряд — больше, чем цикл готов терпеть: уведомление
        // уходит в неотвеченные. Как только модель ответит хоть чему, к нему
        // возвращаются, а не бросают навсегда
        FlakyNetwork llm = new FlakyNetwork(6);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(10));

        // три конца ходов: первый — когда попытка исчерпана и уведомление
        // отложено, второй — для Дашинного, третий — когда она вернулась к
        // отложенному
        CountDownLatch finished = new CountDownLatch(3);
        loop.onNotificationDone(ignored -> finished.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет"));
        for (int waited = 0; waited < 100 && loop.strandedCount() == 0; waited++) {
            Thread.sleep(20);
        }
        assertEquals(1, loop.strandedCount(), "уведомление не отложено до выздоровления модели");
        assertEquals(0, loop.queued(), "отложенное не должно толпиться в общей очереди");

        loop.submit(new Notification("Даша написала: как дела?"));
        assertTrue(finished.await(5, TimeUnit.SECONDS), "неотвеченное так и не вернулось в разговор");
        worker.interrupt();

        assertEquals(8, llm.calls(), "к отложенному уведомлению не вернулись");
        assertEquals(0, loop.strandedCount(), "неотвеченное осталось висеть");
    }

    @Test
    void strandedNotificationProbesItselfWithoutNewMessage() throws Exception {
        FlakyNetwork llm = new FlakyNetwork(6);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(10));
        loop.retryPause(Duration.ofMillis(1));
        loop.strandedProbePause(Duration.ZERO);
        loop.strandedTtl(Duration.ofHours(2));
        CountDownLatch done = new CountDownLatch(2);
        loop.onNotificationDone(ignored -> done.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет"));
        assertTrue(done.await(5, TimeUnit.SECONDS), "проба не вернула отложенное сама");
        worker.interrupt();

        assertEquals(7, llm.calls());
        assertEquals(0, loop.strandedCount());
        assertEquals(1, copiesOf(loop.context(), "Серёжа написал: привет"));
    }

    @Test
    void failedProbesUseOneBackoffWindowAndDoNotDuplicateContext() throws Exception {
        ProbeCountingNetwork llm = new ProbeCountingNetwork();
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(10));
        loop.retryPause(Duration.ofMillis(1));
        loop.strandedProbePause(Duration.ofMillis(500));
        loop.strandedTtl(Duration.ofHours(2));
        CountDownLatch parked = new CountDownLatch(2);
        AtomicInteger failures = new AtomicInteger();
        loop.onNotificationDone(ignored -> parked.countDown());
        loop.onNotificationFailed((n, e) -> failures.incrementAndGet());

        Thread worker = start(loop);
        loop.submit(new Notification("первое отложенное"));
        loop.submit(new Notification("второе отложенное"));
        assertTrue(parked.await(5, TimeUnit.SECONDS), "оба уведомления не отложились");
        failures.set(0);

        assertTrue(llm.firstProbe.await(5, TimeUnit.SECONDS), "первая проба не началась");
        assertEquals(1, copiesOf(loop.context(), "первое отложенное"));
        assertEquals(1, copiesOf(loop.context(), "второе отложенное"));
        assertFalse(llm.secondProbe.await(300, TimeUnit.MILLISECONDS),
                "в одном окне бэкоффа сделано больше одной пробы");
        assertTrue(llm.secondProbe.await(4, TimeUnit.SECONDS), "вторая проба не началась");
        assertEquals(0, failures.get(), "неуспешные пробы снова вызвали onNotificationFailed");
        worker.interrupt();
    }

    @Test
    void strandedProbesFollowFiveTenFifteenMinuteLadder() throws Exception {
        MutableClock clock = new MutableClock();
        Instant t0 = clock.instant();
        LadderNetwork llm = new LadderNetwork();
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(10));
        loop.retryPause(Duration.ofMillis(1));
        loop.strandedProbePause(Duration.ofMinutes(5));
        loop.strandedTtl(Duration.ofHours(2));
        loop.clock(clock);
        CountDownLatch parked = new CountDownLatch(1);
        loop.onNotificationDone(ignored -> parked.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("лестница проб"));
        assertTrue(parked.await(5, TimeUnit.SECONDS), "уведомление не отложилось");
        assertEquals(t0.plus(Duration.ofMinutes(5)), loop.nextStrandedProbeAt());

        Duration[] probeAt = {Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30)};
        Duration[] nextAt = {Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(45)};
        Duration elapsed = Duration.ZERO;
        for (int i = 0; i < probeAt.length; i++) {
            Duration almost = probeAt[i].minusSeconds(1);
            clock.advance(almost.minus(elapsed));
            assertFalse(llm.probes.tryAcquire(100, TimeUnit.MILLISECONDS),
                    "проба " + (i + 1) + " раньше срока");
            clock.advance(Duration.ofSeconds(1));
            elapsed = probeAt[i];
            assertTrue(llm.probes.tryAcquire(5, TimeUnit.SECONDS), "проба " + (i + 1) + " не началась");
            awaitNextProbe(loop, t0.plus(nextAt[i]));
        }
        worker.interrupt();
    }

    /** Часы двигать можно только после того, как неуспешная проба назначила следующую. */
    private static void awaitNextProbe(NotificationLoop loop, Instant expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!(expected.equals(loop.nextStrandedProbeAt()) && loop.strandedCount() == 1)) {
            assertTrue(System.nanoTime() < deadline,
                    "следующая проба не назначена на " + expected + ", а " + loop.nextStrandedProbeAt());
            Thread.onSpinWait();
        }
    }

    @Test
    void expiredStrandedNotificationReturnsChatToUnread() throws Exception {
        MutableClock clock = new MutableClock();
        FlakyNetwork llm = new FlakyNetwork(6);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(10));
        loop.retryPause(Duration.ofMillis(1));
        loop.strandedProbePause(Duration.ofHours(1));
        loop.strandedTtl(Duration.ofMinutes(1));
        loop.clock(clock);
        CountDownLatch parked = new CountDownLatch(1);
        loop.onNotificationDone(ignored -> parked.countDown());
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        loop.submit(new Notification("старое уведомление").from(777));
        assertTrue(parked.await(5, TimeUnit.SECONDS));
        clock.advance(Duration.ofSeconds(61));

        assertEquals(777, marks.take().chatId());
        worker.interrupt();
        assertEquals(0, loop.strandedCount());
    }

    @Test
    void discardMatchingRemovesStrandedNotification() throws Exception {
        FlakyNetwork llm = new FlakyNetwork(6);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(10));
        loop.retryPause(Duration.ofMillis(1));
        loop.strandedProbePause(Duration.ofHours(1));
        loop.strandedTtl(Duration.ofHours(2));
        CountDownLatch parked = new CountDownLatch(1);
        loop.onNotificationDone(ignored -> parked.countDown());
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        loop.submit(new Notification("старое уведомление").from(777));
        assertTrue(parked.await(5, TimeUnit.SECONDS));
        loop.discardMatching("старое уведомление");

        assertEquals(0, loop.strandedCount());
        assertEquals(0, marks.count());
        worker.interrupt();
    }

    @Test
    void freshMessageDuringFailedProbeDiscardsOldStrandedNotification() throws Exception {
        BlockingProbeNetwork llm = new BlockingProbeNetwork();
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(10));
        loop.retryPause(Duration.ofMillis(1));
        loop.strandedProbePause(Duration.ZERO);
        loop.strandedTtl(Duration.ofHours(2));
        CountDownLatch freshDone = new CountDownLatch(1);
        CountDownLatch oldDone = new CountDownLatch(1);
        AtomicBoolean probing = new AtomicBoolean();
        loop.onNotificationDone(notification -> {
            if (notification.text().contains("новое уведомление")) {
                freshDone.countDown();
            }
            if (notification.text().contains("старое уведомление") && probing.get()) {
                oldDone.countDown();
            }
        });
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        String tag = ru.rainedev.raine.phone.Notifications.openingTag(777);
        loop.submit(new Notification(tag + "старое уведомление").from(777));
        assertTrue(llm.probeStarted.await(5, TimeUnit.SECONDS), "проба не началась");
        probing.set(true);

        loop.discardMatching(tag);
        loop.submit(new Notification(tag + "новое уведомление").from(777));
        llm.releaseProbe.countDown();

        assertTrue(freshDone.await(5, TimeUnit.SECONDS), "новое уведомление не обработано");
        assertEquals(0, loop.strandedCount());
        assertEquals(0, loop.queued(), "устаревшее уведомление вернулось в очередь");
        assertFalse(oldDone.await(200, TimeUnit.MILLISECONDS),
                "устаревшее уведомление обработано повторно");
        assertEquals(0, marks.count());
        worker.interrupt();
    }

    @Test
    void busyEndpointDoesNotWipeTheConversation() throws Exception {
        // в отказе занята модель — и текст её ошибки упоминает json; это про
        // эндпоинт, а не про наш контекст, а живой разговор из-за него терять
        // нельзя
        FlakyNetwork llm = new FlakyNetwork(6,
                () -> new LlmFailure(503, "Ошибка эндпоинта (503): invalid json in upstream reply"));
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(10));

        CountDownLatch abandoned = new CountDownLatch(1);
        loop.onNotificationFailed((n, e) -> abandoned.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет"));
        assertTrue(abandoned.await(5, TimeUnit.SECONDS), "сбой занятой модели не отдали уведомление");
        worker.interrupt();

        assertEquals(1, copiesOf(loop.context(), "Серёжа написал: привет"),
                "занятая модель стёрла живой разговор");
    }

    @Test
    void rejectedRequestStillClearsTheContext() throws Exception {
        // а вот когда отвергнут сам запрос, контекст и правда испорчен:
        // с ним не проходит ни один следующий шаг
        FlakyNetwork llm = new FlakyNetwork(1,
                () -> new LlmFailure(400, "Ошибка эндпоинта (400): cannot parse json of messages"));
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));

        CountDownLatch abandoned = new CountDownLatch(1);
        loop.onNotificationFailed((n, e) -> abandoned.countDown());

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет"));
        assertTrue(abandoned.await(5, TimeUnit.SECONDS), "отвергнутый запрос не разобран");
        for (int waited = 0; waited < 100 && loop.context().size() > 0; waited++) {
            Thread.sleep(20);
        }
        worker.interrupt();

        assertTrue(loop.context().isEmpty(), "испорченный контекст не сброшен");
    }

    @Test
    void hopelessOutageGivesUpInsteadOfSpinning() throws Exception {
        // сеть не вернулась совсем: важно, что цикл не встанет намертво на одном уведомлении
        FlakyNetwork llm = new FlakyNetwork(Integer.MAX_VALUE);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(10));

        Thread worker = start(loop);
        loop.submit(new Notification("тебе написали"));

        Thread.sleep(600);
        assertEquals(1, loop.strandedCount(), "безнадёжное уведомление брошено совсем");
        worker.interrupt();

        assertTrue(llm.calls() >= 6, "попыток должно быть несколько, а вышло " + llm.calls());
        assertTrue(llm.calls() < 40, "цикл не должен долбиться в стену без остановки: " + llm.calls());
        assertEquals(0, loop.queued(), "безнадёжное уведомление не должно остаться в очереди навсегда");
    }

    @Test
    void aMessageSheNeverAnsweredLeavesItsChatUnread() throws Exception {
        // эндпоинт отвергает сам запрос: повторять бессмысленно, уведомление
        // потеряно. Его чат к этому моменту открыт, значит прочитан, и догон после
        // перезапуска уже ничего не найдёт — ровно так пропало сообщение владельца
        FlakyNetwork llm = new FlakyNetwork(1,
                () -> new LlmFailure(400, "Ошибка эндпоинта (400): cannot parse json of messages"));
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет").from(777));

        Notification lost = marks.take();
        worker.interrupt();
        assertEquals(777, lost.chatId(), "потерянное уведомление не вернуло чат в непрочитанные");
        assertEquals(1, marks.count(), "второй раз снимать отметку с того же чата незачем");
    }

    @Test
    void aNotificationStillWaitingForTheModelLeavesTheChatRead() throws Exception {
        // пять пауз кончились, уведомление отложено — но она ещё может к нему
        // вернуться в этом же процессе. Снять отметку сейчас значило бы поднять
        // разговор, который она собирается продолжить сама
        FlakyNetwork llm = new FlakyNetwork(6);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(10));
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет").from(777));
        for (int waited = 0; waited < 100 && loop.strandedCount() == 0; waited++) {
            Thread.sleep(20);
        }
        assertEquals(1, loop.strandedCount(), "уведомление не отложено до выздоровления модели");
        assertEquals(0, marks.count(), "отложенное уведомление сняло отметку о прочтении слишком рано");
        worker.interrupt();
    }

    @Test
    void whatShutdownLeavesBehindGoesBackToUnread() throws Exception {
        // процесс выключается с отложенным уведомлением на руках: в этом процессе
        // она к нему уже не вернётся, так что пусть ждёт догона
        FlakyNetwork llm = new FlakyNetwork(Integer.MAX_VALUE);
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        loop.retryPause(Duration.ofMillis(10));
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет").from(777));
        for (int waited = 0; waited < 100 && loop.strandedCount() == 0; waited++) {
            Thread.sleep(20);
        }
        worker.interrupt();

        Notification lost = marks.take();
        worker.join(2000);
        assertEquals(777, lost.chatId(), "выключение бросило чат прочитанным");
        assertEquals(0, loop.strandedCount(), "отложенное осталось висеть после остановки");
    }

    @Test
    void aSuspendedAccountGoesBackToUnread() throws Exception {
        // баланс кончился: до перезапуска она не ответит ни на что, и второе
        // уведомление умирает, даже не начавшись
        FlakyNetwork llm = new FlakyNetwork(Integer.MAX_VALUE,
                () -> new LlmFailure(402, "Ошибка эндпоинта (402): не хватает баланса"));
        NotificationLoop loop = new NotificationLoop(llm, () -> "системный промпт", new Toolbox(), 40_000);
        loop.idleCheck(Duration.ofMillis(20));
        UnreadMark marks = new UnreadMark();
        marks.watch(loop);

        Thread worker = start(loop);
        loop.submit(new Notification("Серёжа написал: привет").from(777));
        loop.submit(new Notification("Даша написала: как дела?").from(888));

        assertEquals(777, marks.take().chatId());
        assertEquals(888, marks.take().chatId(), "приостановленный чат остался прочитанным");
        worker.interrupt();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-27T00:00:00Z");

        void advance(Duration step) {
            now = now.plus(step);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ProbeCountingNetwork implements LlmClient {
        final CountDownLatch firstProbe = new CountDownLatch(1);
        final CountDownLatch secondProbe = new CountDownLatch(2);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            int call = calls.incrementAndGet();
            if (call > 12) {
                if (call == 13) firstProbe.countDown();
                if (call >= 13) secondProbe.countDown();
            }
            throw new UncheckedIOException("Сеть недоступна", new IOException("offline"));
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static final class LadderNetwork implements LlmClient {
        final java.util.concurrent.Semaphore probes = new java.util.concurrent.Semaphore(0);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            if (calls.incrementAndGet() > 6) {
                probes.release();
            }
            throw new UncheckedIOException("Сеть недоступна", new IOException("offline"));
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    private static final class BlockingProbeNetwork implements LlmClient {
        final CountDownLatch probeStarted = new CountDownLatch(1);
        final CountDownLatch releaseProbe = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            int call = calls.incrementAndGet();
            if (call <= 6) {
                throw new UncheckedIOException("Сеть недоступна", new IOException("offline"));
            }
            if (call == 7) {
                probeStarted.countDown();
                try {
                    assertTrue(releaseProbe.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw new UncheckedIOException("Сеть недоступна", new IOException("offline"));
            }
            Message message = new Message(Message.Role.ASSISTANT, "",
                    List.of(new ToolCall("call_wait", "function",
                            new ToolCall.Function("wait", "{}"))), null, null);
            return new ChatResponse("id", "test",
                    List.of(new ChatResponse.Choice(0, message, "tool_calls")),
                    new ChatResponse.Usage(10, 0, 10));
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {0};
        }
    }

    /** Сколько чатов цикл потребовал вернуть в непрочитанные. */
    private static final class UnreadMark {
        private final java.util.concurrent.BlockingQueue<Notification> abandoned =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.util.concurrent.atomic.AtomicInteger seen =
                new java.util.concurrent.atomic.AtomicInteger();

        void watch(NotificationLoop loop) {
            loop.backToUnread(notification -> {
                seen.incrementAndGet();
                abandoned.add(notification);
            });
        }

        /** Ждёт пять секунд: потеря наступает не сразу, а после попыток. */
        Notification take() throws InterruptedException {
            Notification lost = abandoned.poll(5, TimeUnit.SECONDS);
            assertNotNull(lost, "уведомление так и не вернуло чат в непрочитанные");
            return lost;
        }

        /** Сколько снятий отметки было всего — take() из этого числа ничего не убавляет. */
        int count() {
            return seen.get();
        }
    }

    /** Сколько раз реплика из уведомления стоит в контексте — дважды быть не должно. */
    private static long copiesOf(List<Message> context, String text) {
        return context.stream().filter(m -> m.content() != null && m.content().contains(text)).count();
    }
}
