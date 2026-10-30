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

    private final LlmClient llm;
    private final java.util.function.Supplier<String> rejectionPrompt;
    private volatile ru.rainedev.raine.config.Config.Repeat limits;
    private final Map<String, double[]> spoken = new LinkedHashMap<>();

    private double indulgence;

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
    public void remember(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String key = normalize(text);
        if (spoken.containsKey(key)) {
            return;
        }
        try {
            spoken.put(key, llm.embedding(text));
        } catch (RuntimeException e) {
            log.debug("Не удалось запомнить сообщение для проверки на повтор: {}", e.getMessage());
        }
        forgetOldest();
    }

    /**
     * @throws LowQualityException если сказанное слишком похоже на уже сказанное
     */
    public void check(String text) {
        if (spoken.isEmpty()) {
            remember(text);
            return;
        }

        // Дословный повтор уже отправленного — не перефраз, и поблажка для
        // перефразованных слов как раз его и пропускала: схожесть равна 1.00,
        // а порог после отказа поднимается выше. В чате это второй тот же текст.
        String key = normalize(text);
        if (spoken.containsKey(key)) {
            refuse("повтор дословно сказанного", text, false);
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
        for (double[] previous : spoken.values()) {
            double similarity = Similarity.cosine(target, previous);
            maxSimilarity = Math.max(maxSimilarity, similarity);
            sum += similarity;
        }
        double avgSimilarity = sum / spoken.size();

        if (maxSimilarity > limits.triggerMax() + indulgence) {
            refuse("повтор сказанного ранее, схожесть %.2f".formatted(maxSimilarity), text, true);
        }
        if (avgSimilarity > limits.triggerAvg() + indulgence) {
            refuse("хождение по кругу, средняя схожесть %.2f".formatted(avgSimilarity), text, true);
        }

        indulgence = 0;
        spoken.put(key, target);
        forgetOldest();
    }

    /**
     * @param indulges копить ли поблажку: у перефразированных слов есть шанс
     *                 стать новыми, а у дословного повтора отправленного — нет.
     */
    private void refuse(String reason, String text, boolean indulges) {
        if (indulges) {
            indulgence += INDULGENCE_STEP;
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

    private void forgetOldest() {
        while (spoken.size() > limits.maxHistory()) {
            spoken.remove(spoken.keySet().iterator().next());
        }
    }
}
