package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        worker.interrupt();

        assertTrue(llm.calls() >= 6, "попыток должно быть несколько, а вышло " + llm.calls());
        assertTrue(llm.calls() < 40, "цикл не должен долбиться в стену без остановки: " + llm.calls());
        assertEquals(0, loop.queued(), "безнадёжное уведомление не должно остаться в очереди навсегда");
        assertEquals(1, loop.strandedCount(), "безнадёжное уведомление брошено совсем");
    }

    /** Сколько раз реплика из уведомления стоит в контексте — дважды быть не должно. */
    private static long copiesOf(List<Message> context, String text) {
        return context.stream().filter(m -> m.content() != null && m.content().contains(text)).count();
    }
}
