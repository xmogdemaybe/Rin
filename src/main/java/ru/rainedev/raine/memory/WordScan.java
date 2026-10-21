package ru.rainedev.raine.memory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Страховка для поиска: обычный канал сравнивает векторы, этот — просто слова.
 * Запрос и текст записи сводятся к одному виду: строчными буквами, без
 * пунктуации, после чего считается, сколько разных слов запроса встретилось
 * в записи.
 * <p>
 * Без стоп-слов, без стемминга: это не второй ранжир, а запасной ход на случай,
 * когда вектор запроса получить не удалось. Слова из одной буквы («и», «в»)
 * не ищем: они есть в каждой записи, потому ни в чём не помогают.
 */
final class WordScan {

    private WordScan() {
    }

    /** Слова запроса: буквы и числа строчными, без пунктуации. */
    static Set<String> words(String cue) {
        Set<String> words = new LinkedHashSet<>();
        for (String word : cue.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() > 1) {
                words.add(word);
            }
        }
        return words;
    }

    /** Сколько разных слов запроса зацепилось в тексте записи. */
    static int overlap(String body, Set<String> cueWords) {
        if (cueWords.isEmpty()) {
            return 0;
        }
        Set<String> bodyWords = new LinkedHashSet<>(
                List.of(body.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")));
        int hits = 0;
        for (String word : cueWords) {
            if (bodyWords.contains(word)) {
                hits++;
            }
        }
        return hits;
    }
}
