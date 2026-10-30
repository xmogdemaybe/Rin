package ru.rainedev.raine.tools;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.memory.Similarity;
import ru.rainedev.raine.phone.Markup;

/**
 * Ловит повторы: самое заметное, что выдаёт бота — одно и то же разными словами.
 * <p>
 * Сравнивает новое сообщение с недавними собственными. Слишком похоже на одно
 * из них — значит разговор исчерпан, и живой человек не стал бы дописывать.
 * Похоже на все сразу в среднем — значит она ходит по кругу, меняя обёртку.
 */
public final class AntiRepeat {

    private static final Logger log = LoggerFactory.getLogger(AntiRepeat.class);


    /**
     * После каждого срабатывания порог временно поднимается. Иначе модель,
     * которой нечего добавить, попадёт в бесконечный отказ: перефразирует,
     * снова получает отказ, и так до предела шагов.
     */
    private static final double INDULGENCE_STEP = 0.07;

    /** Сколько слов уже сказанного показывать модели в отказе. */
    private static final int QUOTE_LIMIT = 200;

    /**
     * Сколько чатов помнить. У каждого свой край по числу сообщений, а без
     * этого края память росла бы вместе со списком всех переписок.
     */
    static final int MAX_CHATS = 64;

    private final LlmClient llm;
    private final java.util.function.Supplier<String> rejectionPrompt;
    private volatile ru.rainedev.raine.config.Config.Repeat limits;

    /** Разговор каждого чата и поблажка, накопленная именно в нём. */
    private final LinkedHashMap<Long, Chat> chats = new LinkedHashMap<>(16, 0.75f, true);

    public AntiRepeat(LlmClient llm, String rejectionPrompt) {
        this(llm, () -> rejectionPrompt);
    }

    public AntiRepeat(LlmClient llm, java.util.function.Supplier<String> rejectionPrompt) {
        this(llm, rejectionPrompt, new ru.rainedev.raine.config.Config.Repeat(0.95, 0.85, 32));
    }

    public AntiRepeat(LlmClient llm, java.util.function.Supplier<String> rejectionPrompt,
                      ru.rainedev.raine.config.Config.Repeat limits) {
        this.llm = llm;
        this.rejectionPrompt = rejectionPrompt;
        this.limits = limits;
    }

    public void limits(ru.rainedev.raine.config.Config.Repeat limits) {
        this.limits = limits;
    }

    /** Сообщения из истории чата — чтобы не повторяться и после перезапуска. */
    public void remember(long chatId, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        Chat chat = chat(chatId);
        String key = normalize(text);
        if (chat.spoken.containsKey(key)) {
            return;
        }
        try {
            chat.spoken.put(key, llm.embedding(text));
        } catch (RuntimeException e) {
            log.debug("Не удалось запомнить сообщение для проверки на повтор: {}", e.getMessage());
        }
        forgetOldest(chat);
    }

    /**
     * @throws LowQualityException если сказанное слишком похоже на уже сказанное
     */
    public void check(long chatId, String text) {
        Chat chat = chat(chatId);
        if (chat.spoken.isEmpty()) {
            remember(chatId, text);
            return;
        }

        // Дословный повтор уже отправленного — не перефраз, и поблажка для
        // перефразованных слов как раз его и пропускала: схожесть равна 1.00,
        // а порог после отказа поднимается выше. В чате это второй тот же текст.
        String key = normalize(text);
        if (chat.spoken.containsKey(key)) {
            refuse(chat, "повтор дословно сказанного", text, false);
        }

        double[] target;
        try {
            target = llm.embedding(text);
        } catch (RuntimeException e) {
            // проверка не обязательна: без неё разговор просто менее строгий
            log.debug("Проверка на повтор пропущена: {}", e.getMessage());
            return;
        }

        double maxSimilarity = 0;
        double sum = 0;
        for (double[] previous : chat.spoken.values()) {
            double similarity = Similarity.cosine(target, previous);
            maxSimilarity = Math.max(maxSimilarity, similarity);
            sum += similarity;
        }
        double avgSimilarity = sum / chat.spoken.size();

        if (maxSimilarity > limits.triggerMax() + chat.indulgence) {
            refuse(chat, "повтор сказанного ранее, схожесть %.2f".formatted(maxSimilarity), text, true);
        }
        if (avgSimilarity > limits.triggerAvg() + chat.indulgence) {
            refuse(chat, "хождение по кругу, средняя схожесть %.2f".formatted(avgSimilarity), text, true);
        }

        chat.indulgence = 0;
        chat.spoken.put(key, target);
        forgetOldest(chat);
    }

    /**
     * Память и поблажка — свои на каждый чат: «спокойной ночи» двоим это не
     * повтор, а отказы в одной переписке не расслабляют другую. Давний без
     * разговора чат вытесняется первым.
     */
    private Chat chat(long chatId) {
        Chat chat = chats.get(chatId);
        if (chat == null) {
            chat = new Chat();
            chats.put(chatId, chat);
            while (chats.size() > MAX_CHATS) {
                chats.remove(chats.keySet().iterator().next());
            }
        }
        return chat;
    }

    /**
     * @param indulges копить ли поблажку: у перефразированных слов есть шанс
     *                 стать новыми, а у дословного повтора отправленного — нет.
     */
    private void refuse(Chat chat, String reason, String text, boolean indulges) {
        if (indulges) {
            chat.indulgence += INDULGENCE_STEP;
        }
        log.info("Отклонено как повтор: {}", reason);
        // без этих слов модель не знает, от чего именно её отвернули, и при
        // низкой температуре выдаёт ту же фразу ещё раз — за ещё один шаг
        throw new LowQualityException(rejectionPrompt.get() + "\n- you already sent exactly this: «"
                + quote(text) + "»");
    }

    /** Сравнение повторов не должно зависеть от пробелов и регистра. */
    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static String quote(String text) {
        String safe = Markup.body(text).replaceAll("\\s+", " ").strip();
        return safe.length() <= QUOTE_LIMIT ? safe : safe.substring(0, QUOTE_LIMIT) + "…";
    }

    private void forgetOldest(Chat chat) {
        while (chat.spoken.size() > limits.maxHistory()) {
            chat.spoken.remove(chat.spoken.keySet().iterator().next());
        }
    }

    /** Недавние реплики одного чата и то, насколько он уже надоел отказами. */
    private static final class Chat {
        private final Map<String, double[]> spoken = new LinkedHashMap<>();
        private double indulgence;
    }
}
