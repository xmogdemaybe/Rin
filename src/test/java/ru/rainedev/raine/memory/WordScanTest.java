package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WordScanTest {

    @Test
    void ignoresCaseAndPunctuation() {
        Set<String> words = WordScan.words("Вика, где ТОРТ?!");

        assertEquals(Set.of("вика", "где", "торт"), words);
        assertEquals(2, WordScan.overlap("Вика испекла торт.", words));
    }

    @Test
    void countsEachQueryWordOnce() {
        Set<String> words = WordScan.words("кот, КОТ и кот пёс");

        assertEquals(Set.of("кот", "пёс"), words);
        assertEquals(2, WordScan.overlap("кот и пёс", words));
        assertEquals(1, WordScan.overlap("только кот тут", words));
        assertEquals(0, WordScan.overlap("щенок на фото", words));
    }

    @Test
    void keepsNumbersAndHyphenParts() {
        // числа и части слов через дефис остаются словами: «2026» должно искаться
        Set<String> words = WordScan.words("июль-2026?");

        assertEquals(Set.of("июль", "2026"), words);
        assertEquals(2, WordScan.overlap("июль 2026", words));
    }

    @Test
    void emptyRequestsAreSafe() {
        assertEquals(0, WordScan.overlap("текст записи", Set.of()));
        assertEquals(Set.of(), WordScan.words("... !!! ???"));
    }
}
