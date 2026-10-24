package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Просмотр истории должен быть виден в логе: «Действия» на шаге называют только
 * тулзу, и по ним не отличить, открыла она историю и разглядела что-то или
 * пролистала впустую.
 */
class StoryWatchLogTest {

    @Test
    void theLogNamesTheStoryAndWhatWasSeen() {
        String note = TelegramTools.watched(5501583141L, 12, "<photo description>\nснег\n</photo>");

        assertTrue(note.contains("Историю 12 чата 5501583141"), note);
        assertTrue(note.contains("снег"), "без содержимого строчка бесполезна: " + note);
    }

    @Test
    void anEmptyViewIsStillAView() {
        // «ничего не разглядели» — тоже исход: он отличает пустую историю
        // от несостоявшегося просмотра
        String note = TelegramTools.watched(7L, 3, "   ");

        assertTrue(note.contains("Историю 3 чата 7"), note);
        assertTrue(note.contains("разглядывать нечего"), note);
    }

    @Test
    void aQuestionWithoutEyesIsNamedUnanswered() {
        // без зрения вопрос про картинку не разбирается: молчать об этом нельзя,
        // иначе модель ответит по памяти, будто смотрела
        String answer = TelegramTools.blindStory("<story caption=\"гуляла\"/>", "какого цвета небо?");

        assertTrue(answer.contains("stays unanswered"), answer);
        assertTrue(answer.contains("caption=\"гуляла\""), "подпись читается и без зрения: " + answer);
        assertFalse(TelegramTools.blindStory("<story/>", "").contains("unanswered"),
                "без вопроса не о чем отчитываться");
        assertTrue(TelegramTools.blindStory("<story/>", null).contains("vision is turned off"),
                "вопрос мог и не прийти: ответ обязан быть на месте");
    }

    @Test
    void theNoteStaysOnOneLine() {
        String note = TelegramTools.watched(7L, 3, "<video description>\nкадр 1\nкадр 2\n</video>");

        assertEquals(0, note.chars().filter(symbol -> symbol == '\n').count(),
                "многострочный просмотр утонул бы между шагами разговора: " + note);
        assertTrue(note.contains("кадр 1 кадр 2"), note);
    }
}
