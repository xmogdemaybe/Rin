package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class CharacterGrowthTest {

    /** Отвечает по сценарию и считает запросы: за ночь их должен быть ровно один. */
    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        int chatCalls;
        String lastSystem;
        String lastRequest;

        ScriptedLlm willAnswer(String text) {
            answers.addLast(text);
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            chatCalls++;
            lastSystem = systemPrompt;
            lastRequest = history.getLast().content();
            Message message = new Message(Message.Role.ASSISTANT,
                    answers.isEmpty() ? "" : answers.removeFirst(), List.of(), null, null);
            return new ChatResponse("id", "test",
                    List.of(new ChatResponse.Choice(0, message, "stop")), ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1, 0, 0};
        }
    }

    private final ScriptedLlm llm = new ScriptedLlm();

    private CharacterGrowth growth(Path dir, Path prompts, String instructions) {
        return new CharacterGrowth(new Diary(dir), llm, () -> instructions,
                prompts.resolve("character_growth.md"));
    }

    private void note(Diary diary, String trait, double intensity, String at) {
        diary.save("Прошла записка про «" + trait + "» — она пролежит здесь надолго.",
                new double[] {1, 0, 0}, 0,
                List.of(new TraitSignal("hurt", trait, -0.5, intensity, "Alex", at)));
    }

    @Test
    void singleWeakNoteEarnsNothing(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-20T10:00:00Z");

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> false));

        assertEquals(0, llm.chatCalls, "один слабый сигнал — ноль запросов");
        assertFalse(Files.exists(prompts.resolve("character_growth.md")));
    }

    @Test
    void corroboratedTraitGrowsTheFile(@TempDir Path dir, @TempDir Path prompts) throws Exception {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z"); // через 17 часов
        llm.willAnswer("- огрызаюсь на колкости");

        assertTrue(growth(dir, prompts, "инструкции").pass(() -> false));

        assertEquals(1, llm.chatCalls);
        assertEquals("инструкции", llm.lastSystem, "шаблон идёт системным промптом");
        assertTrue(llm.lastRequest.contains("огрызаюсь на колкости"));
        assertTrue(llm.lastRequest.contains("noted: 2"), "повтор должен быть виден числом");
        assertTrue(Files.readString(prompts.resolve("character_growth.md"))
                .contains("огрызаюсь на колкости"));
    }

    @Test
    void flashbulbSingleNoteCountsOnItsOwn(@TempDir Path dir, @TempDir Path prompts) throws Exception {
        Diary diary = new Diary(dir);
        note(diary, "сжимается, когда замечает, что я поднял голос", 0.95, "2026-10-20T18:00:00Z");
        llm.willAnswer("- сжимается, когда замечает, что я поднял голос");

        assertTrue(growth(dir, prompts, "инструкции").pass(() -> false));

        assertEquals(1, llm.chatCalls);
        assertTrue(Files.readString(prompts.resolve("character_growth.md")).startsWith("- сжимается"));
    }

    @Test
    void twoNotesInOneEveningAreNotACorroboration(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.5, "2026-10-20T21:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.5, "2026-10-20T22:30:00Z"); // 1.5 часа — подряд

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> false));
        assertEquals(0, llm.chatCalls);
    }

    @Test
    void overflowingAnswerIsRefusedAndOldFileStays(@TempDir Path dir, @TempDir Path prompts) throws Exception {
        Path file = prompts.resolve("character_growth.md");
        Files.writeString(file, "- старая черта остаётся при всех отказ");
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z");
        llm.willAnswer(String.join("\n",
                IntStream.rangeClosed(1, 41).mapToObj(i -> "- строчка " + i).toList()));

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> false),
                "переполненный файл не переписывается — владельцу лучше глянуть");

        assertEquals("- старая черта остаётся при всех отказ", Files.readString(file).strip());
    }

    @Test
    void toolCallMarkupIsRefused(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z");
        llm.willAnswer("<invoke name=\"record_trait_signal\">мусор</invoke>");

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> false));

        assertFalse(Files.exists(prompts.resolve("character_growth.md")));
    }

    @Test
    void staleNotesArePastTheirChance(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.5, "2026-08-20T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.5, "2026-08-29T10:00:00Z");

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> false));
        assertEquals(0, llm.chatCalls, "заметкам старше двух недель переживать нечего");
    }

    @Test
    void wakingUpInterruptsBeforeTheRequest(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z");

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> true));

        assertEquals(0, llm.chatCalls);
    }

    @Test
    void emptyAnswerIsSkipped(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z");
        llm.willAnswer("");

        assertFalse(growth(dir, prompts, "инструкции").pass(() -> false));

        assertEquals(1, llm.chatCalls);
        assertFalse(Files.exists(prompts.resolve("character_growth.md")));
    }
}
