package ru.rainedev.raine.core;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.phone.Markup;

/**
 * Порыв написать в собственный канал.
 * <p>
 * Живёт отдельным запросом к модели, а не через цикл уведомлений. Публикация в
 * канал не должна ни стирать контекст разговора с владельцем, ни печатать в нём
 * ленту канала: общий список сообщений на это и не рассчитан.
 * <p>
 * Инструментов у запроса нет — модель только пишет текст. Решает код: вышел ли
 * пост, не повторяется ли он и пора ли ещё.
 */
public final class ChannelImpulse implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ChannelImpulse.class);

    /**
     * Между порывами — от шести до восьми часов. Разговорному хватает получаса,
     * но пост в канал виден не одному человеку, и лента из трёх постов в день
     * читается как спам.
     */
    static final int MIN_HOURS = 6;
    static final int MAX_HOURS = 8;

    /** Срабатывает примерно в каждом четвёртом случае — вдвое реже разговорного порыва. */
    static final double CHANCE = 0.25;

    /** Столько же часов должно пройти с собственной публикации, чтобы пробовать снова. */
    static final Duration COOLDOWN = Duration.ofHours(MIN_HOURS);

    private static final String PROMPT = """
            Write one post for your own channel. It is yours: you speak there to everyone who reads it, \
            not as an answer to somebody.
              - What you wrote there recently is listed below. Do not repeat it, its subject or its turns of phrase.
            %s
              - One post of your own. If there is truly nothing to say right now, answer exactly #none.""";

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("rin-channel").factory());

    private final LlmClient llm;
    private final Supplier<String> systemPrompt;
    private final RandomGenerator random;

    private volatile long chatId;
    private volatile BiFunction<Long, String, String> publish =
            (id, text) -> "Nothing is wired to publish there. Nothing sent.";
    private volatile Supplier<String> recentPosts = () -> "";
    private volatile BooleanSupplier blocked = () -> false;
    private volatile Supplier<Instant> clock = Instant::now;
    private volatile Instant lastPublished = Instant.EPOCH;

    public ChannelImpulse(LlmClient llm, Supplier<String> systemPrompt, RandomGenerator random) {
        this.llm = llm;
        this.systemPrompt = systemPrompt;
        this.random = random;
    }

    /** Ноль — своего канала нет, и порывов не будет вовсе. */
    public void channel(long chatId) {
        this.chatId = chatId;
    }

    /**
     * Чем публиковать: номер канала и текст, обратно — что ответил путь публикации.
     * Успехом считается только ответ, начинающийся с «Post published».
     */
    public void publishWith(BiFunction<Long, String, String> publisher) {
        this.publish = publisher;
    }

    /** Последние её посты: после перезапуска копилка повторов пуста, а лента остаётся. */
    public void recentPosts(Supplier<String> posts) {
        this.recentPosts = posts;
    }

    /** Пока она отдыхает или канал недоступен, порыв проходит мимо. */
    public void blockedWhen(BooleanSupplier blocked) {
        this.blocked = blocked;
    }

    /** Часы: кулдаун и момент публикации отсчитываются по ним, в проверках они свои. */
    public void clock(Supplier<Instant> clock) {
        this.clock = clock;
    }

    public void start() {
        if (chatId == 0) {
            log.info("Своего канала нет — порывов публиковать не будет");
            return;
        }
        scheduleNext();
        log.info("Порывы в свой канал: через {}–{} часов, примерно в каждом четвёртом случае",
                MIN_HOURS, MAX_HOURS);
    }

    /** Ровный интервал читался бы как расписание, поэтому каждый раз новый. */
    long nextDelayMinutes() {
        return TimeUnit.HOURS.toMinutes(MIN_HOURS + random.nextInt(MAX_HOURS - MIN_HOURS + 1));
    }

    private void scheduleNext() {
        scheduler.schedule(this::tick, nextDelayMinutes(), TimeUnit.MINUTES);
    }

    private void tick() {
        try {
            act();
        } catch (RuntimeException e) {
            log.error("Порыв опубликовать в свой канал не удался", e);
        } finally {
            scheduleNext();
        }
    }

    /** Для проверок: попробовать прямо сейчас, не дожидаясь своего времени. */
    void actNow() {
        act();
    }

    private void act() {
        if (chatId == 0 || blocked.getAsBoolean()) {
            return;
        }
        if (!cooldownExpired()) {
            log.debug("Свой канал свеж после публикации — порыв проходит мимо");
            return;
        }
        if (random.nextDouble() >= CHANCE) {
            return;
        }
        String post = compose();
        if (post.isEmpty()) {
            return;
        }
        String answer = publish.apply(chatId, post);
        if (answer != null && answer.startsWith("Post published")) {
            lastPublished = clock.get();
            log.info("Пост в свой канал вышел");
            return;
        }
        log.info("Пост в свой канал не вышел: {}",
                ErrorNote.of(answer == null ? "публикация ничего не ответила" : answer));
    }

    /** Пора ли пробовать: с последней вышедшей публикации должно пройти шесть часов. */
    boolean cooldownExpired() {
        return Duration.between(lastPublished, clock.get()).compareTo(COOLDOWN) >= 0;
    }

    /**
     * Запрос целиком свой: ни переписки с владельцем, ни инструментов. Всё, что
     * модель напишет для канала, остаётся в канале.
     *
     * @return текст поста или пустая строка, если публиковать нечего
     */
    String compose() {
        String written = recentPosts.get();
        String prompt = PROMPT.formatted(written.isBlank()
                ? ""
                : "  - <your_recent_posts>\n" + Markup.body(written).strip() + "\n    </your_recent_posts>");
        ChatResponse response = llm.chat(systemPrompt.get(), List.of(Message.user(prompt)), null);
        String text = response.text() == null ? "" : response.text().strip();
        // «#none» — её собственный отказ писать, а длиннее поста Telegram не
        // примет: резать пополам значит оставить рубленую фразу на видном месте
        if (text.isBlank() || text.startsWith("#none") || text.length() > 4096) {
            return "";
        }
        return text;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
