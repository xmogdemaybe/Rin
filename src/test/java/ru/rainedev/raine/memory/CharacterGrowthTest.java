package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
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

    /** Часы стоят на той же ночи, что и заметки: иначе они состарятся вместе с календарём. */
    private static final Clock NIGHT =
            Clock.fixed(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC);

    /** Сколько строк было в ответе, от которого файл отказался. */
    private final List<Long> overflowAlerts = new ArrayList<>();

    private CharacterGrowth growth(Path dir, Path prompts, String instructions) {
        CharacterGrowth growth = new CharacterGrowth(new Diary(dir), llm, () -> instructions,
                prompts.resolve("character_growth.md"), NIGHT);
        growth.onOverflow(overflowAlerts::add);
        return growth;
    }

    private void note(Diary diary, String trait, double intensity, String at) {
        diary.save("Прошла записка про «" + trait + "» — она пролежит здесь надолго.",
                new double[] {1, 0, 0}, 0,
                List.of(new TraitSignal("hurt", trait, -0.5, intensity, "Alex", at)), Source.GENERAL);
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
    void questionDoesNotGrowWithTheDiary(@TempDir Path dir, @TempDir Path prompts) throws Exception {
        // заметок за месяцы набирается больше, чем влезает в ответ: без предела
        // вопрос раздувался, ответ упирался в лимит строк — и файл не переписывался
        Diary diary = new Diary(dir);
        for (int i = 0; i < 60; i++) {
            note(diary, String.format("черта %03d", i), 0.9 + 0.001 * i, "2026-10-20T18:00:00Z");
        }
        llm.willAnswer("- короткая запись");

        assertTrue(growth(dir, prompts, "инструкции").pass(() -> false));

        long asked = llm.lastRequest.lines().filter(line -> line.startsWith("- ")).count();
        assertEquals(CharacterGrowth.MAX_GROUPS, asked, "в вопрос ушло лишнее: " + asked);
        assertTrue(llm.lastRequest.contains("черта 059"), "самая сильная остаётся");
        assertFalse(llm.lastRequest.contains("черта 000"), "слабейшая уступает место");
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
    void overflowingAnswerIsRefusedAndOwnerIsToldOnce(@TempDir Path dir, @TempDir Path prompts)
            throws Exception {
        Path file = prompts.resolve("character_growth.md");
        Files.writeString(file, "- старая черта остаётся при всех отказ");
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z");
        CharacterGrowth growth = growth(dir, prompts, "инструкции");
        llm.willAnswer(rambling());

        assertFalse(growth.pass(() -> false), "переполненный файл не переписывается");
        assertEquals(List.of(41L), overflowAlerts, "владелец слышит, сколько строк не влезло");

        llm.willAnswer(rambling());
        assertFalse(growth.pass(() -> false));
        assertEquals(List.of(41L), overflowAlerts, "ночь за ночью одно и то же не повторяют");

        assertEquals("- старая черта остаётся при всех отказ", Files.readString(file).strip());
    }

    @Test
    void ownerHearsAboutOverflowAgainAfterAGoodNight(@TempDir Path dir, @TempDir Path prompts) {
        Diary diary = new Diary(dir);
        note(diary, "огрызаюсь на колкости", 0.4, "2026-10-19T10:00:00Z");
        note(diary, "огрызаюсь на колкости", 0.6, "2026-10-20T03:00:00Z");
        CharacterGrowth growth = growth(dir, prompts, "инструкции");
        llm.willAnswer(rambling());
        growth.pass(() -> false);

        llm.willAnswer("- огрызаюсь на колкости");
        assertTrue(growth.pass(() -> false), "нормальная ночь проходит");

        llm.willAnswer(rambling());
        growth.pass(() -> false);
        assertEquals(List.of(41L, 41L), overflowAlerts, "после удачной ночи отказ снова слышно");
    }

    private static String rambling() {
        return String.join("\n", IntStream.rangeClosed(1, 41).mapToObj(i -> "- строчка " + i).toList());
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
