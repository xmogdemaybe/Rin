package ru.rainedev.raine.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Разбор чисел из ответа модели.
 * <p>
 * Идентификаторы сообщений и чатов модель выдаёт как придётся: числом, строкой,
 * а иногда — в научной записи вроде {@code 7.576e+08}. Обычное чтение целого
 * в таких случаях молча даёт ноль, и реакция, пересылка, правка или удаление
 * промахиваются мимо сообщения, ничего не сообщая. Поэтому берём то, что дали,
 * и приводим к числу сами.
 */
public final class Numbers {

    private static final Logger log = LoggerFactory.getLogger(Numbers.class);

    private Numbers() {}

    /**
     * Наибольшее целое, которое double хранит без потери цифры: два в 53-й степени минус один.
     */
    private static final double EXACT_LIMIT = 9_007_199_254_740_991D;

    /** @return число из поля, если его вообще удалось прочитать */
    public static Optional<Long> longFrom(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return Optional.of(node.longValue());
        }
        if (node.isFloatingPointNumber()) {
            return exact(node.doubleValue(), node.asText());
        }
        String text = node.asText("").strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            // может оказаться дробным или в научной записи
        }
        try {
            return exact(Double.parseDouble(text), text);
        } catch (NumberFormatException e) {
            log.warn("Не удалось прочитать число из {}", node);
            return Optional.empty();
        }
    }

    /**
     * Дробное число становится целым только тогда, когда целое в нём настоящее:
     * без дробной части и не больше {@link #EXACT_LIMIT}.
     * <p>
     * Идентификатор стикера — 18–19 знаков, и шлюз отдаёт его через double, то
     * есть уже округлённым («5.4117508770590394e+17» вместо
     * «541175087705903941»). Молча превратить такое в целое значит взять
     * соседний стикер из того же пака — он ведь существует. Пустой ответ
     * честнее: инструмент попросит прислать id текстом, как он напечатан.
     */
    private static Optional<Long> exact(double value, String written) {
        if (value != Math.rint(value) || Math.abs(value) > EXACT_LIMIT) {
            log.warn("Идентификатор {} пришёл дробным числом — точного целого в нём нет", written);
            return Optional.empty();
        }
        return Optional.of((long) value);
    }

    /**
     * Признак «да/нет» из поля. Модель присылает то настоящий булев,
     * то строку «true» — понимаем оба.
     */
    public static boolean flagAt(JsonNode arguments, String field) {
        JsonNode node = arguments == null ? null : arguments.get(field);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        return node.isBoolean() ? node.booleanValue() : "true".equalsIgnoreCase(node.asText("").strip());
    }

    /** То же, но из поля объекта. */
    public static Optional<Long> longAt(JsonNode arguments, String field) {
        return longFrom(arguments == null ? null : arguments.get(field));
    }

    /** @param fallback чем заменить, если прочитать не вышло */
    public static long longAt(JsonNode arguments, String field, long fallback) {
        return longAt(arguments, field).orElse(fallback);
    }

    /**
     * Список идентификаторов в одном поле: модель пишет их то через запятую,
     * то через пробел, то настоящим JSON-списком. Мусорное слово среди номеров
     * пропускаем, а не выбрасываем весь запрос; числа в научной записи читаем
     * так же, как в одиночном поле, — иначе id молча терялись и удаление
     * промахивалось мимо сообщения.
     */
    public static List<Long> idsAt(JsonNode arguments, String field) {
        List<Long> ids = new ArrayList<>();
        JsonNode given = arguments == null ? null : arguments.get(field);
        if (given == null || given.isNull() || given.isMissingNode()) {
            return ids;
        }
        if (given.isArray()) {
            for (JsonNode item : given) {
                longFrom(item).ifPresent(ids::add);
            }
            return ids;
        }
        for (String part : given.asText("").split("[,;\\s]+")) {
            longFrom(JsonNodeFactory.instance.textNode(part)).ifPresent(ids::add);
        }
        return ids;
    }
}
