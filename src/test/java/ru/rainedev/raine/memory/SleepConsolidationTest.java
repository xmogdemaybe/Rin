package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.llm.ChatResponse;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

class SleepConsolidationTest {

    private static final class ScriptedLlm implements LlmClient {
        private final Deque<String> answers = new ArrayDeque<>();
        /** Что уходило модели на переписку — по одному элементу на группу. */
        final List<String> bodies = new ArrayList<>();
        int chatCalls;

        ScriptedLlm willAnswer(String text) {
            answers.addLast(text);
            return this;
        }

        @Override
        public ChatResponse chat(String systemPrompt, List<Message> history, JsonNode tools) {
            chatCalls++;
            bodies.add(history.isEmpty() ? "" : history.get(0).content());
            String text = answers.isEmpty() ? "" : answers.removeFirst();
            Message message = new Message(Message.Role.ASSISTANT, text, List.of(), null, null);
            return new ChatResponse("id", "test", List.of(new ChatResponse.Choice(0, message, "stop")),
                    ChatResponse.Usage.EMPTY);
        }

        @Override
        public double[] embedding(String input) {
            return new double[] {1, 0, 0};
        }
    }

    /** Сколько записей модель просили переписать одним куском. */
    private static long entriesIn(String body) {
        return body.lines().filter(line -> line.startsWith("{\"confidence\"")).count();
    }

    private SleepConsolidation consolidation(Diary diary, ScriptedLlm llm, Path archive) {
        return new SleepConsolidation(diary, llm, "перепиши", archive, 4000, new Random(1));
    }

    @Test
    void mergedEntriesReplaceTheOldOnes(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Ездили на Байкал в июле, было холодно", new double[] {1, 0, 0});
        diary.save("Байкал запомнился прозрачной водой", new double[] {1, 0, 0});

        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("{\"confidence\":0.5}\nПоездка на Байкал в июле: холодно, вода прозрачная.");

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        Diary after = new Diary(dir);
        assertEquals(1, after.size(), "две близкие записи должны стать одной");
        assertTrue(after.query(new double[] {1, 0, 0}).getFirst().entry().body().contains("прозрачная"));
    }

    @Test
    void oldEntriesGoToArchiveNotToOblivion(@TempDir Path dir) throws IOException {
        // алгоритм переписывает память необратимо — возможность вернуться дороже места
        Diary diary = new Diary(dir);
        diary.save("Что-то, что перепишут", new double[] {1, 0, 0});

        Path archive = dir.resolve("archive");
        consolidation(diary, new ScriptedLlm().willAnswer("{\"confidence\":0.5}\nПереписанная запись."), archive)
                .run(Duration.ofMinutes(5), () -> false);

        assertTrue(Files.isDirectory(archive), "архив должен появиться");
        try (var files = Files.list(archive)) {
            assertEquals(1, files.count(), "прежняя запись обязана сохраниться в архиве");
        }
    }

    @Test
    void aMergedEntryKeepsTheStrictestLabel(@TempDir Path dir) {
        // сведение переписывает память необратимо: если общая запись утянет за
        // собой чужую личку, секрет станет общим и достанется кому попало
        Diary diary = new Diary(dir);
        diary.save("Ездили на Байкал в июле, было холодно", new double[] {1, 0, 0}, 0, List.of(), Source.OWNER_DM);
        diary.save("Байкал запомнился прозрачной водой", new double[] {1, 0, 0});

        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("{\"confidence\":0.5}\nПоездка на Байкал в июле: холодно, вода прозрачная.");

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        Diary after = new Diary(dir);
        assertEquals(1, after.size(), "две записи свелись в одну");
        assertEquals(Source.OWNER_DM, after.query(new double[] {1, 0, 0}).getFirst().entry().metadata().source(),
                "сведённая запись наследует строжайшую метку из исходных");
    }

    @Test
    void establishedFactsAreNeverRewritten(@TempDir Path dir) throws IOException {
        Diary diary = new Diary(dir);
        diary.save("Установленный факт", new double[] {1, 0, 0});
        // помечаем как факт вручную: такие записи алгоритм трогать не должен
        Path file;
        try (var files = Files.list(dir)) {
            file = files.filter(p -> p.toString().endsWith(".md")).findFirst().orElseThrow();
        }
        Files.writeString(file, Files.readString(file).replace("\"confidence\":0.0", "\"confidence\":1.0"));

        consolidation(new Diary(dir), new ScriptedLlm().willAnswer("{\"confidence\":0.5}\nПопытка переписать факт."),
                dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertTrue(Files.exists(file), "факт должен остаться на месте");
    }

    @Test
    void forgottenEntriesAreNotStored(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Незначительная мелочь", new double[] {1, 0, 0});

        consolidation(diary, new ScriptedLlm().willAnswer("{\"confidence\":-1.0}\nэто стоит забыть"),
                dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertEquals(0, new Diary(dir).size(), "помеченное к забвению не сохраняется");
    }

    @Test
    void diaryIsGoneOverAgainWhileTheNightLasts(@TempDir Path dir) {
        // за долгую ночь память пересматривается глубже одного прохода,
        // но круг, не изменивший ничего, — последний: остаток ночи проходит спокойно
        Diary diary = new Diary(dir);
        for (int i = 0; i < 4; i++) {
            diary.save("Запись номер " + i + " про поездку и разговоры", new double[] {1, 0, 0});
        }

        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("{\"confidence\":0.5}\nСведено, заход первый.")
                .willAnswer("{\"confidence\":0.5}\nСведено, заход второй.")
                .willAnswer("{\"confidence\":0.5}\nСведено, заход третий.");

        // короткий предел объёма: записи сводятся по нескольку, а не все разом
        new SleepConsolidation(diary, llm, "перепиши", dir.resolve("archive"), 20, new Random(1))
                .run(Duration.ofMinutes(5), () -> false);

        assertTrue(llm.chatCalls >= 2, "после первого прохода работа продолжается: " + llm.chatCalls);
        assertTrue(llm.chatCalls < 20, "и всё же останавливается, а не крутится всю ночь: " + llm.chatCalls);
    }

    @Test
    void unrelatedEntriesAreNotFused(@TempDir Path dir) {
        // векторы подобраны так, что любая пара ниже половины: это «никак не
        // связано», и в одну переписку такое тянуть нечем
        double[][] apart = {{1, 1, 1}, {1, -1, -1}, {-1, 1, -1}, {-1, -1, 1}};
        Diary diary = new Diary(dir);
        for (int i = 0; i < apart.length; i++) {
            diary.save("Запись про разное номер " + i, apart[i]);
        }
        ScriptedLlm llm = new ScriptedLlm()
                .willAnswer("{\"confidence\":0.5}\nПереписанная запись, длинная и целая.");

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertFalse(llm.bodies.isEmpty(), "пересмотр был");
        for (String body : llm.bodies) {
            assertEquals(1, entriesIn(body), "в одну переписку попало несвязанное: " + body);
        }
    }

    @Test
    void groupHasAHardCeiling(@TempDir Path dir) {
        // короткие записи предела по объёму не достигают никогда: без потолка
        // за одну ночь сводился бы весь дневник одним куском
        Diary diary = new Diary(dir);
        for (int i = 0; i < 20; i++) {
            diary.save("Коротко " + i, new double[] {1, 0, 0});
        }
        ScriptedLlm llm = new ScriptedLlm();
        for (int i = 0; i < 30; i++) {
            llm.willAnswer("{\"confidence\":0.5}\nСведённая запись номер " + i + " с запасом по длине.");
        }

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertFalse(llm.bodies.isEmpty());
        for (String body : llm.bodies) {
            assertTrue(entriesIn(body) <= 6, "группа выросла без потолка: " + entriesIn(body));
        }
    }

    @Test
    void wakingUpStopsTheWork(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        for (int i = 0; i < 5; i++) {
            diary.save("Запись номер " + i, new double[] {i, 1, 0});
        }
        ScriptedLlm llm = new ScriptedLlm();

        consolidation(diary, llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> true);

        assertEquals(0, llm.chatCalls, "если её позвали, память не пересматривается");
    }

    @Test
    void garbageFromModelIsNotStored(@TempDir Path dir) {
        Diary diary = new Diary(dir);
        diary.save("Настоящая запись", new double[] {1, 0, 0});

        consolidation(diary, new ScriptedLlm()
                        .willAnswer("<｜｜DSML｜｜tool_calls>")
                        .willAnswer("<｜｜DSML｜｜tool_calls>")
                        .willAnswer("<｜｜DSML｜｜tool_calls>"),
                dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        Diary after = new Diary(dir);
        assertEquals(1, after.size(), "прежняя запись остаётся на месте");
        assertFalse(after.query(new double[] {1, 0, 0}).getFirst().entry().body().contains("DSML"));
    }

    @Test
    void emptyDiaryIsNotAProblem(@TempDir Path dir) {
        ScriptedLlm llm = new ScriptedLlm();

        consolidation(new Diary(dir), llm, dir.resolve("archive")).run(Duration.ofMinutes(5), () -> false);

        assertEquals(0, llm.chatCalls);
    }
}
