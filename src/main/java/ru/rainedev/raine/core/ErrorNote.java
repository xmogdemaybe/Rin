package ru.rainedev.raine.core;

import java.util.regex.Pattern;

/**
 * Тело ответа чужого эндпоинта попадает в текст ошибки, а текст ошибки — в
 * журнал, в data/pending и в контекст модели. Шлюз отвечает им не только
 * по делу: в тексте бывают заголовки запроса, чужие строки и куски ключей,
 * поэтому наверх идёт только короткая выжимка — одна строка, без разметки
 * и без последовательностей, похожих на секрет.
 */
public final class ErrorNote {

    /** Сколько смысла умещается в сообщении об ошибке: дальше уже не суть, а простыня. */
    public static final int LIMIT = 200;

    /** «api_key: sk-...», «Authorization: Bearer ...» — название остаётся, значение исчезает. */
    private static final Pattern LABELLED = Pattern.compile(
            "(?i)\\b(api[_ -]?key|token|secret|authorization|password|cookie)\\b([\"':=\\s]+)\\S+");

    /** Форма ключа: сплошная длинная последовательность из знаков, допустимых в base64. */
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9+/=_-]{24,}");

    /** Открывающая скопка перед буквой или слэхом: чужая разметка не должна закрывать секции. */
    private static final Pattern TAG_LIKE = Pattern.compile("<(?=[a-zA-Z/])");

    private ErrorNote() {}

    /** @return одна строка не длиннее {@link #LIMIT}, в которой нечего украсть
     *          и чем сломать разметку контекста
     */
    public static String of(String body) {
        String text = body == null ? "" : body.strip();
        text = text.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ");
        text = TAG_LIKE.matcher(text).replaceAll("");
        text = LABELLED.matcher(text).replaceAll("$1$2[скрыто]");
        text = OPAQUE.matcher(text).replaceAll("[скрыто]");
        return text.length() > LIMIT ? text.substring(0, LIMIT) + "…" : text;
    }
}
