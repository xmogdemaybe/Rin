package ru.rainedev.raine.phone;

/**
 * Единственный формат служебных блоков снимка чата — полки своих стикеров и
 * пояснения к сообщениям — и единственное место, где они вырезаются.
 * <p>
 * Раньше теги жили каждый у себя: {@code StickerTools} писал их прямо в сборке
 * полки, {@code DiaryWriter} держал свою пару констант, а {@code WorkingMemory}
 * и {@code NotificationLoop} о полке не знали ничего. Из-за этого в живом
 * контексте 18.11 оказалось три побайтово одинаковых копии полки по 4 719 знаков
 * (20% запроса), а в запросе трёхдневной сводки — десять копий на 49 850 знаков
 * (39,6%). Полка приклеивается к каждому снимку чата, и та же лексика
 * «chibi / anime-style / cat ears» уезжала в модель по нескольку раз на дню,
 * чем и кормилась фильтр-политика.
 */
public final class ScreenMarkup {

    /** Полка своих стикеров: её приклеивают к каждому снимку чата. */
    public static final String SHELF_OPEN = "<favourite_stickers>";

    /** Закрытие полки. */
    public static final String SHELF_CLOSE = "</favourite_stickers>";

    /** Пояснение снимка: какой это чат и как в нём себя вести. */
    public static final String NOTES_OPEN = "<instructions>";

    /** Закрытие пояснения. */
    public static final String NOTES_CLOSE = "</instructions>";

    private ScreenMarkup() {}

    /** Есть ли в тексте полка — по открывающему тегу, до полного разбора. */
    public static boolean hasShelf(String text) {
        return text != null && text.contains(SHELF_OPEN);
    }

    /** Есть ли в тексте пояснение снимка чата. */
    public static boolean hasNotes(String text) {
        return text != null && text.contains(NOTES_OPEN);
    }

    /** Вырезает полку вместе с разметкой. */
    public static String stripShelf(String text) {
        return cut(text, SHELF_OPEN, SHELF_CLOSE);
    }

    /** Вырезает полку и пояснения: этим вызовам нужен только сам разговор. */
    public static String stripScreen(String text) {
        return cut(cut(text, SHELF_OPEN, SHELF_CLOSE), NOTES_OPEN, NOTES_CLOSE);
    }

    /**
     * Вырезает блоки между разметкой; незакрытый блок уносит всё до конца текста:
     * обрывок чужого пояснения без конца — это уже не слова разговора, а
     * недоделанная указка, которую модель обязана исполнять.
     */
    public static String cut(String text, String open, String close) {
        StringBuilder kept = new StringBuilder();
        int from = 0;
        for (int start = text.indexOf(open); start >= 0; start = text.indexOf(open, from)) {
            kept.append(text, from, start);
            int end = text.indexOf(close, start);
            if (end < 0) {
                return kept.toString();
            }
            from = end + close.length();
        }
        return kept.append(text, from, text.length()).toString();
    }
}
