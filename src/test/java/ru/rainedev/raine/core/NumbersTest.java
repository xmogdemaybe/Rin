package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class NumbersTest {

    private static ObjectNode arguments(String json) {
        try {
            return (ObjectNode) new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void plainNumberIsRead() {
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":757600000}"), "id", 0));
    }

    @Test
    void scientificNotationIsRead() {
        // некоторые модели выдают идентификаторы именно так
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":7.576e+08}"), "id", 0));
    }

    @Test
    void stringIsRead() {
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":\"757600000\"}"), "id", 0));
        assertEquals(757600000L, Numbers.longAt(arguments("{\"id\":\"7.576e+08\"}"), "id", 0));
    }

    @Test
    void missingAndGarbageAreEmpty() {
        assertTrue(Numbers.longAt(arguments("{}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":null}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":\"\"}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":\"какой-то текст\"}"), "id").isEmpty());
    }

    @Test
    void negativeChatIdSurvives() {
        // идентификаторы групп отрицательные и очень длинные
        assertEquals(-1001234567890L, Numbers.longAt(arguments("{\"id\":-1001234567890}"), "id", 0));
    }

    /**
     * Id стикера — 18–19 знаков: в double младшие цифры теряются ещё до нас.
     * Молча превратить такое в целое значит взять соседний стикер того же пака —
     * ровно то, что происходило в живом разговоре.
     */
    @Test
    void anIdTooLongForFloatIsRefused() {
        assertTrue(Numbers.longAt(arguments("{\"id\":5.4117508770590394e+17}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":\"5.1998461070263e+18\"}"), "id").isEmpty());
    }

    @Test
    void aLongIdIsExactWhenItArrivesWhole() {
        assertEquals(541175087705903941L, Numbers.longAt(arguments("{\"id\":541175087705903941}"), "id", 0));
        assertEquals(541175087705903941L, Numbers.longAt(arguments("{\"id\":\"541175087705903941\"}"), "id", 0));
    }

    @Test
    void aFractionIsNotAnId() {
        assertTrue(Numbers.longAt(arguments("{\"id\":12.5}"), "id").isEmpty());
        assertTrue(Numbers.longAt(arguments("{\"id\":\"12.5\"}"), "id").isEmpty());
    }
}
