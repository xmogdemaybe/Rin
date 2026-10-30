package ru.rainedev.raine.tools;

import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/**
 * Держит текстовые лица в переписке на коротком поводке.
 * <p>
 * «:3», «>_<» и «<3» идут про ласку, а не про каждую реплику. Но стоит им
 * начаться, как они лезут во все сообщения подряд: вечером 30 октября восемь
 * реплик — и в восьми лицо. Никакого словаря настроений и догадки, милый
 * момент или нет: только счётчик. Лицо разрешено раз в N реплик этого чата,
 * в остальных срезается.
 */
public final class Kaomoji {

    /**
     * Цифра рядом превращает «:3» во что угодно, только не в лицо: в «12:30»
     * будильник, а не умиление.
     */
    private static final Pattern FACE = Pattern.compile(">_<|(?<!\\d):3(?!\\d)|<3");

    /** Скобки, из которых лицо уже вышло. */
    private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\( *\\)");

    /** Пробел, оставшийся лицом перед знаком препинания: «хватит . иди». */
    private static final Pattern SPACE_BEFORE_MARK = Pattern.compile(" +([,.!?;:])");

    /**
     * Сколько чатов помнить. Счётчики росли бы вместе со списком всех
     * переписок, а давний без разговора чат всё равно начинает с чистого.
     */
    static final int MAX_CHATS = 64;

    /** Реплики, прошедшие с последнего разрешённого лица в каждом чате. */
    private final LinkedHashMap<Long, Integer> since = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Пропускает реплику или вычищает из неё лица.
     *
     * @param every через сколько реплик этого чата лицо уместно снова; 0 — не считывать
     * @return та же реплика либо вычищенная; пусто — если от неё осталось одно лицо
     */
    public String gate(long chatId, String piece, int every) {
        if (!FACE.matcher(piece).find()) {
            plain(chatId);
            return piece;
        }
        if (rested(chatId) >= every) {
            since.put(chatId, 0);
            return piece;
        }
        plain(chatId);
        return clean(FACE.matcher(piece).replaceAll(" "));
    }

    /**
     * Срезанное лицо не должно оставлять за собой следов: двойных пробелов,
     * пустых скобок и отлетевшей на знак препинания точки.
     */
    private static String clean(String text) {
        String one = EMPTY_BRACKETS.matcher(text).replaceAll("");
        one = SPACE_BEFORE_MARK.matcher(one).replaceAll("$1");
        return one.replaceAll(" {2,}", " ").strip();
    }

    /** Реплика без лица — бюджет подрастает: лицо имеет шанс вернуться. */
    private void plain(long chatId) {
        Integer passed = since.get(chatId);
        since.put(chatId, passed == null ? 0 : passed + 1);
        while (since.size() > MAX_CHATS) {
            since.remove(since.keySet().iterator().next());
        }
    }

    /**
     * В чате, где лицо ещё не появлялось, бюджет уже полон: первая ласка в
     * разговоре не из-под запретителя, она и так к месту.
     */
    private int rested(long chatId) {
        Integer passed = since.get(chatId);
        return passed == null ? Integer.MAX_VALUE : passed;
    }
}
