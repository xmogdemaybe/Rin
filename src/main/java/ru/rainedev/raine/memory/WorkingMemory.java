package ru.rainedev.raine.memory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.LongConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Средняя память — то, что человек держит в голове день-три: обещания,
 * незакрытые дела, настроение и самочувствие.
 * <p>
 * Дневник помнит события, но не помнит, что она обещала перезвонить и что
 * весь день сегодня раздражена. Эта часть дописывается в системный промпт
 * и потому влияет на тон каждого ответа.
 * <p>
 * Размер ограничен: модель обязана переносить прежние пункты дословно, так что
 * без предела файл рос бы каждый пересказ, утяжеляя каждый запрос и приближая
 * переполнение контекста. Обрезанное не молчит — о переполнении слышит владелец.
 */
public final class WorkingMemory {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemory.class);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"));

    private static final int MAX_ATTEMPTS = 3;

    /**
     * Сколько символов памяти уходит в системный промпт. Вопрос модели просит
     * 100—500 слов: это примерно столько и есть, с запасом на даты и разметку
     * списка. Файл каждый ход уходит в контекст, поэтому длиннее — дорого.
     */
    public static final int MAX_LENGTH = 4000;

    private static final String EMOTIONS = "sad, arousal, betrayed, grief, sorrow, pensiveness, anger, annoyed, "
            + "fear, amazement, surprise, distracted, love, trust, accepted, joy, serenity, embarrassed, proud, "
            + "envious, jealous, grateful, disappointed, hopeful, lonely, nostalgic, relieved, resentful";

    private static final String PHYSICAL = "energetic, tired, exhausted, sleepy, restless, tense, relaxed, hungry, "
            + "thirsty, sore, dizzy, nauseous, feverish, cold, warm, comfortable, uncomfortable, in_pain, injured, "
            + "healthy, sick, weak, stiff, trembling, numb, wet, dry, stimulated";

    private final Path file;
    private final LlmClient llm;
    private final String characterName;

    /**
     * Кому сказать, что память не влезла в отведённый размер, и сколько символов
     * было. По умолчанию некому — обрезка остаётся только в журнале.
     */
    private volatile LongConsumer overflowAlert = chars -> { };

    /** Один отказ на случай: сказать раз, а не на каждом запросе к модели. */
    private volatile boolean overflowTold;

    public WorkingMemory(Path file, LlmClient llm, String characterName) {
        this.file = file;
        this.llm = llm;
        this.characterName = characterName;
    }

    /** Куда сообщить о переполнении: владелец должен знать, что файл пора разбирать руками. */
    public void onOverflow(LongConsumer alert) {
        this.overflowAlert = alert;
    }

    /**
     * Файл чистится и при чтении тоже, а не только при записи: его правят руками,
     * переносят с прежней машины, а иногда там оседает разметка чужой модели —
     * и всё это уходит прямо в системный промпт, где ему совсем не место.
     * Там же память обрезается до {@link #MAX_LENGTH}, если разрослась.
     */
    public String read() {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            return bounded(ModelText.unwrap(Files.readString(file), "things_to_remember"), "в файле");
        } catch (IOException e) {
            log.warn("Не удалось прочитать рабочую память: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Дополнение к системному промпту. Состояние тела и настроения задаёт тон
     * ответов — поэтому оно и попадает в промпт, а не в обычное сообщение.
     */
    public String asPromptSuffix() {
        String remembered = read();
        if (remembered.isEmpty()) {
            return "";
        }
        return """
                <things_to_remember>
                %s
                </things_to_remember>
                <instructions>
                Your behaviour must be highly influenced by "physical state" and "emotional state" mentioned above.

                <example>
                Emotional state: anger
                ...
                send_telegram_message("text":"иди нахуй заебал")
                </example>
                <example>
                Emotional state: amused
                ...
                send_telegram_message("text":"мррр~")
                </example>
                </instructions>
                """.formatted(remembered);
    }

    /** Пересобирает память по свежему разговору, сохраняя незакрытые дела. */
    public void update(String systemPrompt, List<Message> context) {
        if (context.isEmpty()) {
            return;
        }
        List<Message> conversation = new ArrayList<>(context);
        conversation.add(Message.user(buildPrompt()));

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String content;
            try {
                content = llm.chat(systemPrompt, conversation, null).text();
            } catch (RuntimeException e) {
                log.warn("Не удалось обновить рабочую память: {}", e.getMessage());
                return;
            }
            if (ModelText.looksLikeToolCall(content)) {
                log.warn("В рабочей памяти оказалась разметка вызова, попытка {} из {}", attempt, MAX_ATTEMPTS);
                conversation.add(Message.user("Do not write tool calls. Answer with plain text only."));
                continue;
            }
            String cleaned = ModelText.unwrap(content, "things_to_remember");
            if (cleaned.isBlank()) {
                // пустой ответ — решение модели, а не обрыв связи: тот же вопрос
                // ещё раз даст тот же результат, а стоит он весь разговор целиком
                log.warn("Модель вернула пустую рабочую память — оставляю прежнюю");
                return;
            }
            String trimmed = bounded(cleaned, "в ответе модели");
            if (trimmed.isBlank()) {
                continue;
            }
            write(trimmed);
            // память снова влезает целиком: прошлое переполнение разобрано
            overflowTold = false;
            return;
        }
        log.warn("Рабочая память не обновлена — оставляю прежнюю");
    }

    private void write(String content) {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            ru.rainedev.raine.storage.AtomicFiles.writeString(file, content + "\n");
            log.info("Рабочая память обновлена, {} символов", content.length());
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить рабочую память", e);
        }
    }

    /**
     * Обрезает разросшуюся память по границе строки: в промпт уходит начало,
     * но целыми строками, а не обрывком последней.
     *
     * @param where откуда текст — для журнала
     */
    private String bounded(String content, String where) {
        if (content.length() <= MAX_LENGTH) {
            return content;
        }
        String kept = content.substring(0, MAX_LENGTH);
        int cut = kept.lastIndexOf('\n');
        if (cut > 0) {
            kept = kept.substring(0, cut);
        }
        log.warn("Рабочая память {} длиннее {} символов — в промпт уходит только начало", where, MAX_LENGTH);
        tellAboutOverflow(content.length());
        return kept.strip();
    }

    /** Владелец слышит о переполнении один раз: дальше его очередь, а не её. */
    private void tellAboutOverflow(long chars) {
        if (overflowTold) {
            return;
        }
        overflowTold = true;
        overflowAlert.accept(chars);
    }

    private String buildPrompt() {
        LocalDate today = LocalDate.now();
        StringBuilder prompt = new StringBuilder("""
                What are important things in timespan %s — %s (3 days) you should remember?
                Do not attempt to make tool_calls or #ask. Your job is to summarize your current tasks and revisit \
                tasks from previous session.
                """.formatted(DATE.format(today.minusDays(3)), DATE.format(today)));

        String previous = read();
        if (!previous.isEmpty()) {
            prompt.append("""

                    Here is the PREVIOUS <things_to_remember> from the last session. You MUST preserve ALL items \
                    verbatim from it, except:
                    1. Completed tasks — mark them as done or remove
                    2. Items that have NOT been updated for more than 3 days — you may forget them

                    Previous working memory:
                    <previous_things_to_remember>
                    %s
                    </previous_things_to_remember>
                    Important: the content of previous_things_to_remember will be overwritten by your next response. \
                    Make sure to preserve:
                    - unfinished tasks (not older than 3 days);
                    - reminders (not older than 3 days or without a deadline)
                    """.formatted(previous));
        }

        prompt.append("""

                You must include:
                - promises
                - reminders
                - unfinished tasks
                - responsibilities
                - %s's current emotional state: %s
                - %s's current physical state: %s
                - other important details

                You must write briefly (100-500 words), structurize output, INCLUDE DATES.
                Hard limit: %d characters for the whole answer. If the list does not fit, drop the \
                oldest items instead of exceeding it — everything past the limit is cut off and lost.
                Each item MUST have a "last updated" date. Example format:
                - Напомнить Алексею про оплату хостинга до 15 мая — последнее обновление: Apr 28
                - Жду ответ от Марии по поводу встречи в пятницу — последнее обновление: Apr 29
                """.formatted(characterName, EMOTIONS, characterName, PHYSICAL, MAX_LENGTH));

        return prompt.toString();
    }
}
