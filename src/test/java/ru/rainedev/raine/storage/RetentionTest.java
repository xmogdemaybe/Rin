package ru.rainedev.raine.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.Transcript;

/**
 * Дампы разговоров с моделью, описания картинок и расшифровки голосовых
 * пишутся, чтобы разобрать вчерашний ответ, — а жили вечно. Набегают тысячи
 * файлов, и когда место кончится, падать начнёт запись дневника, то есть
 * память, а не эта мелочь. Срок у них обязан быть.
 */
class RetentionTest {

    @TempDir Path dir;

    /**
     * Часы теста: возраст файлов и дедлайн прохода считаются от одной и той же
     * отметки, иначе проверка границы зависела бы от того, успеет ли машина
     * вернуться в тот же миллисекундный интервал.
     */
    private final long now = System.currentTimeMillis();

    private Path touch(String name, Duration ago) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "содержимое");
        Files.setLastModifiedTime(file, FileTime.fromMillis(now - ago.toMillis()));
        return file;
    }

    @Test
    void aTempFilePastItsDeadlineIsGone() throws IOException {
        Path stale = touch("старый.json", Duration.ofDays(15));
        Path fresh = touch("свежий.json", Duration.ofDays(13));

        assertEquals(1, Retention.prune(dir, Retention.AGE, now));

        assertFalse(Files.exists(stale), "протухшая запись должна уйти");
        assertTrue(Files.exists(fresh), "вчерашний дамп ещё нужен для разбора");
    }

    /** Граница срока: ровно две недели — ещё не просрочено. */
    @Test
    void theDeadlineItselfIsNotLateYet() throws IOException {
        Path edge = touch("ровно-в-срок.json", Retention.AGE);

        assertEquals(0, Retention.prune(dir, Retention.AGE, now));

        assertTrue(Files.exists(edge), "файл ровно в возрасте срока остаётся");
    }

    @Test
    void garbageIsFoundInSubfoldersButFoldersSurvive() throws IOException {
        Path nested = touch("logs/llm/старый-вложенный.json", Duration.ofDays(20));

        assertEquals(1, Retention.prune(dir, Retention.AGE, now));

        assertFalse(Files.exists(nested), "вложенный мусор — тоже мусор");
        assertTrue(Files.isDirectory(dir.resolve("logs/llm")), "папку не трогаем: в неё пишут снова");
    }

    @Test
    void anAbsentFolderIsNotAnEmergency() {
        assertEquals(0, Retention.prune(dir.resolve("такой-папки-нет"), Retention.AGE, now),
                "папки под сроком могут вообще не создаться");
    }

    /** Продуваются все папки списка, а не первая попавшаяся. */
    @Test
    void everyFolderOnTheListGetsItsTurn() throws IOException {
        Path first = dir.resolve("logs/llm");
        Path second = dir.resolve("cache/images");
        Files.createDirectories(first);
        Files.createDirectories(second);
        touch("logs/llm/просрочка.json", Duration.ofDays(15));
        touch("cache/images/просрочка.md", Duration.ofDays(15));

        assertEquals(2, Retention.pruneAll(List.of(first, dir.resolve("нет-такой"), second), Retention.AGE),
                "проход идёт по всем папкам списка и не спотыкается о несуществующую");
    }

    @Test
    void theSweepCoversThePlacesThatActuallyGrow() throws IOException {
        List<Path> targets = Retention.targets(config(""));
        assertTrue(targets.contains(Transcript.directory()),
                "дампы запросов к модели — самый быстрый способ раздуть диск");
        assertTrue(targets.contains(Path.of("cache/images")), "кэш описаний картинок");
        assertTrue(targets.contains(Path.of("cache/hearing")), "кэш услышанного");
        assertTrue(targets.contains(Path.of("data/voice_messages")), "записанные голосовые");
    }

    /**
     * Уборка идёт по списку папок, и ошибка в этом списке стоит дневника:
     * он живёт в тех же data/, что и голосовые.
     */
    @Test
    void herMemoryIsNeverOnTheList() throws IOException {
        Config config = config("""
                diary_dir=data/diary
                session_dir=session
                camera_gallery=data/gallery
                """);
        List<Path> targets = Retention.targets(config);
        List<Path> protectedDirs = List.of(config.diaryDir(), config.diaryDir().resolve("archive"),
                config.diaryDir().resolveSibling("pending"), config.sessionDir(), config.camera().gallery());
        for (Path kept : protectedDirs) {
            for (Path target : targets) {
                assertFalse(target.startsWith(kept), kept + " — под срок подводить нельзя");
                // папка под сроком не вправе накрыть память целиком: data/ вместо
                // data/voice_messages съело бы и дневник, и отложенные разговоры
                assertFalse(kept.startsWith(target), target + " накрывает " + kept);
            }
        }
        assertFalse(targets.contains(config.workingMemoryFile()),
                "файл средней памяти — не временный");
    }

    private Config config(String extra) throws IOException {
        Path file = dir.resolve("config.properties");
        Files.writeString(file, """
                api_id=1
                api_hash=test-hash
                phone=+70000000000
                llm_base_url=http://primary.invalid/v1
                llm_model=primary
                llm_api_key=test-primary-key
                """ + extra);
        return Config.load(file);
    }
}
