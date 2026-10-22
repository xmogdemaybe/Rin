package ru.rainedev.raine.telegram;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Ответы Telegram, которые за один ход спрашиваются по нескольку раз.
 * <p>
 * Число обращений к Telegram ограничено, а одно и то же сообщение проверяется
 * и на цитату, и на пересылку, и на правку. Держать такое вечно нельзя —
 * текст правят, — поэтому запись живёт считанные секунды: внутри одного хода
 * это один запрос вместо пяти, а между ходами данные всегда свежие.
 * <p>
 * Живёт бот неделями, а ключей — чаты и сообщения — за это время набираются
 * тысячи. Поэтому мест здесь ровно столько, сколько нужно одному ходу:
 * самое старое вытесняется, и память не растёт вместе с перепиской.
 *
 * @param <K> ключ
 * @param <V> что хранится
 */
final class ShortLivedCache<K, V> {

    private record Entry<V>(V value, long at) {}

    /** Сколько записей умещается в один ход с запасом. */
    private static final int MAX_ENTRIES = 512;

    private final Map<K, Entry<V>> entries = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<K, Entry<V>> eldest) {
                    return size() > MAX_ENTRIES;
                }
            });

    private final long lifetimeMillis;

    ShortLivedCache(long lifetimeMillis) {
        this.lifetimeMillis = lifetimeMillis;
    }

    /** Считает значение, если его нет или оно устарело. */
    V get(K key, Function<K, V> compute) {
        Entry<V> entry = entries.get(key);
        if (entry != null && System.currentTimeMillis() - entry.at() < lifetimeMillis) {
            return entry.value();
        }
        // значение считается вне замка карты: вычисление — это обращение к
        // Telegram, а под замком оно останавливало бы и другие чаты
        V value = compute.apply(key);
        put(key, value);
        return value;
    }

    /** Запомнить свежий ответ, который посчитали снаружи. */
    void put(K key, V value) {
        if (value != null) {
            entries.put(key, new Entry<>(value, System.currentTimeMillis()));
        } else {
            entries.remove(key);
        }
    }

    /** То же для запросов, которые могут ничего не вернуть. */
    Optional<V> optional(K key, Function<K, Optional<V>> compute) {
        return Optional.ofNullable(get(key, k -> compute.apply(k).orElse(null)));
    }

    void forget(K key) {
        entries.remove(key);
    }

    void clear() {
        entries.clear();
    }

    /** Для проверок: сколько мест занято сейчас. */
    int size() {
        return entries.size();
    }
}
