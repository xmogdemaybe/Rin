package ru.rainedev.raine.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.Transcript;

/**
 * Срок у того, что пишется впрок и больше никогда не нужно.
 * <p>
 * Дамп запроса к модели, описание картинки и расшифровка голосовой нужны,
 * чтобы разобрать вчерашний ответ. Без срока они живут месяцами: набегают
 * тысячи файлов, а когда место на диске кончится, падать начнёт запись
 * дневника — то есть память, а не эта мелочь.
 */
public final class Retention {

    private static final Logger log = LoggerFactory.getLogger(Retention.class);

    /** Две недели: активная отладка, выходные и один долгий разбор влезают целиком. */
    public static final Duration AGE = Duration.ofDays(14);

    private Retention() {}

    /**
     * Папки под сроком — только то, что можно потерять и добыть заново: дампы
     * запросов к модели, описания картинок, расшифровки и уже отданные голосовые.
     * Дневник, отложенные разговоры и галерея снимков так не работают: их
     * перечитывают месяцами, а снимок из галереи она может послать и спустя
     * неделю. Скачанное Telegram удаляет сама библиотека — руками в её каталоге
     * трогать нечего, база останется с битыми ссылками.
     */
    public static List<Path> targets(Config config) {
        return List.of(Transcript.directory(), config.vision().cacheDir(),
                config.hearing().cacheDir(), config.voice().directory());
    }

    /** Один проход по всем папкам под сроком. */
    public static int pruneAll(List<Path> dirs, Duration age) {
        int removed = 0;
        for (Path dir : dirs) {
            removed += prune(dir, age);
        }
        return removed;
    }

    /** @return сколько файлов убрано */
    public static int prune(Path dir, Duration age) {
        return prune(dir, age, System.currentTimeMillis());
    }

    /**
     * Убираются только файлы, папки остаются на месте: в них пишут снова, и
     * удалённый каталог означал бы, что следующий писатель должен догадаться
     * создать его заново.
     */
    static int prune(Path dir, Duration age, long now) {
        if (!Files.isDirectory(dir)) {
            // папка могла вообще не создаться: голос выключен, снимков не было
            return 0;
        }
        long deadline = now - age.toMillis();
        int removed = 0;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < deadline && Files.deleteIfExists(file)) {
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("Временный файл {} не удалился: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось обойти {} на предмет просрочки: {}", dir, e.getMessage());
        }
        if (removed > 0) {
            log.info("Убрано файлов старше {} суток: {} ({})", age.toDays(), removed, dir);
        }
        return removed;
    }
}
