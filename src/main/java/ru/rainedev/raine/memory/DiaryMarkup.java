package ru.rainedev.raine.memory;

/**
 * Единственный формат служебных блоков памяти вида {@code your_diary_page} и
 * единственное место, где они вырезаются.
 * <p>
 * До этого класс страница собиралась одним способом в {@link DiaryMemory},
 * вторым — в {@code Spontaneity}, а вырезалась третьим: {@code DiaryWriter}
 * искал закрытие {@code </your_diary_page>} точной строкой, хотя генератор
 * закрывал блок длинным тегом с теми же словами, что и в открытии. Закрытие не
 * находилось, и вырезание уносило весь остаток сообщения — вместе со словами
 * человека, ради которых ход и был. Теперь атрибуты живут только в открытии,
 * закрытие всегда короткое, а {@link #strip(String)} терпим к обоим видам: и к
 * новому, и к длинному, который мог остаться в контексте до этой правки.
 */
public final class DiaryMarkup {

    /** Страница дневника в контексте разговора: её нельзя копировать дословно. */
    private static final String PAGE_OPEN =
            "<your_diary_page additional_context just_for_reasoning no_plagiarism no_copy>";

    /** Повод порыва — та же страница, но звать её воспоминанием в скобках незачем. */
    private static final String REASON_OPEN = "<your_diary_page just_for_reasoning no_plagiarism no_copy>";

    /** Закрытие, которое теперь ставится всегда — короткое, без атрибутов. */
    private static final String CLOSE = "</your_diary_page>";

    /** Начало закрывающего тега — и короткого, и бывшего длинного: по нему вырезаем. */
    private static final String CLOSE_PREFIX = "</your_diary_page";

    /** Начало открывающего тега: без пробела и без {@code >} дальше — не наш блок. */
    private static final String OPEN_PREFIX = "<your_diary_page";

    private DiaryMarkup() {}

    /**
     * Вклейка страницы дневника в начало сообщения: кому она доступна и что в ней.
     *
     * @param attribution строка вида {@code [from: …]} или пустая, если зрителя нет
     * @param body        текст записи без разметки
     */
    public static String page(String attribution, String body) {
        StringBuilder out = new StringBuilder(PAGE_OPEN).append('\n');
        if (!attribution.isEmpty()) {
            out.append(attribution).append('\n');
        }
        return out.append(body).append('\n').append(CLOSE).append("\n\n").toString();
    }

    /** Повод порыва: одна страница, без права дословно её повторять. */
    public static String reason(String body) {
        return REASON_OPEN + "\n" + body + "\n" + CLOSE + "\n\n";
    }

    /** Есть ли в тексте хоть одна страница памяти — до полного разбора. */
    public static boolean hasPage(String text) {
        return text != null && text.contains(OPEN_PREFIX);
    }

    /**
     * Вырезает блоки памяти вместе с разметкой. Закрывающий тег принимается
     * любой длины — короткий и бывший длинный, — потому что контекст и
     * отложенные на диске разговоры могли остаться с прежним закрытием.
     * <p>
     * Незакрытый блок уносит остаток текста, как это делало прежнее вырезание:
     * обрывок разметки без конца — это уже не слова разговора, а чужая вклейка,
     * у которой отрезали только начало.
     */
    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder kept = new StringBuilder();
        int from = 0;
        while (true) {
            int start = text.indexOf(OPEN_PREFIX, from);
            if (start < 0) {
                return kept.append(text, from, text.length()).toString();
            }
            char next = text.charAt(Math.min(start + OPEN_PREFIX.length(), text.length() - 1));
            if (next != ' ' && next != '>') {
                // "<your_diary_pageXYZ>" — не наш блок, оставляем как есть
                kept.append(text, from, start + OPEN_PREFIX.length());
                from = start + OPEN_PREFIX.length();
                continue;
            }
            int open = text.indexOf('>', start);
            int close = open < 0 ? -1 : text.indexOf(CLOSE_PREFIX, open);
            int after = close < 0 ? -1 : text.indexOf('>', close);
            if (after < 0) {
                return kept.append(text, from, start).toString();
            }
            kept.append(text, from, start);
            from = after + 1;
        }
    }
}
