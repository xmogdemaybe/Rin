package ru.rainedev.raine.phone;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Как сообщение выглядело в прошлый раз, когда она его читала.
 * <p>
 * Прежних слов Telegram не отдаёт: событие о правке приносит одну отметку
 * времени. Отсчётом служит её собственное чтение — что показали, то и
 * запомнили. Правят обычно по нескольку раз подряд, и между двумя прочтениями
 * важна перемена от того, что она уже видела, а не от промежуточной правки,
 * которой она не видала.
 * <p>
 * Бот живёт неделями, а сообщений в переписке тысячи, поэтому память здесь
 * тесная и короткая: самое старое вытесняется, а вчерашний текст перестаёт быть
 * доводом — за сутки его успевают поправить не раз, и сверка с ним показывала бы
 * перемену, которой давно уже не было.
 */
public final class MessageEdits {

    /** Ни чего не помнит: правка остаётся короткой пометкой, без прежних слов. */
    public static final MessageEdits NONE = new MessageEdits(0, 0L);

    /** Сколько сообщений умещается: дальше вытесняют самое старое прочитанное. */
    private static final int MAX_ENTRIES = 512;

    /** Сколько живут прежние слова. */
    private static final long LIFETIME_MILLIS = 24 * 60 * 60 * 1000L;

    private record Seen(String text, long at) {}

    private final Map<String, Seen> entries;
    private final long lifetimeMillis;

    public MessageEdits() {
        this(MAX_ENTRIES, LIFETIME_MILLIS);
    }

    /**
     * @param places        сколько мест; ноль — не запоминать ничего
     * @param lifetimeMillis сколько живёт запись, в миллисекундах
     */
    MessageEdits(int places, long lifetimeMillis) {
        this.lifetimeMillis = lifetimeMillis;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Seen> eldest) {
                return size() > places;
            }
        });
    }

    /** Что стояло в сообщении в прошлое её чтение; пусто, если чтение первое. */
    public Optional<String> previous(long chatId, long messageId) {
        Seen seen = entries.get(key(chatId, messageId));
        if (seen == null || System.currentTimeMillis() - seen.at() > lifetimeMillis) {
            return Optional.empty();
        }
        return Optional.of(seen.text());
    }

    /** Что показали — то и запомнили: следующее чтение сверяется с этим. */
    public void remember(long chatId, long messageId, String text) {
        entries.put(key(chatId, messageId), new Seen(text, System.currentTimeMillis()));
    }

    /** Номера сообщений повторяются от чата к чату. */
    private static String key(long chatId, long messageId) {
        return chatId + ":" + messageId;
    }
}
