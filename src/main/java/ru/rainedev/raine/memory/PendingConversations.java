package ru.rainedev.raine.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.Message;

/**
 * Разговор, который не удалось пересказать в дневник, ждёт на диске.
 * <p>
 * Пересказ делает модель, а модель — за сетью. Оборвалась связь на остановке —
 * и день, прожитый в оперативной памяти, исчезал вместе с процессом. Здесь он
 * ложится сырым и разбирается при следующем запуске; в худшем случае его можно
 * прочитать глазами — файл человекочитаемый.
 */
public final class PendingConversations {

    private static final Logger log = LoggerFactory.getLogger(PendingConversations.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path dir;

    public PendingConversations(Path dir) {
        this.dir = dir;
    }

    /** Отложенное: сам разговор и то, откуда он пришёл. */
    public record Pending(Origin origin, List<Message> conversation) {}

    /** Отложить разговор целиком — со всеми вызовами инструментов, как он был. */
    public void keep(List<Message> conversation, Origin origin) {
        if (conversation.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(System.currentTimeMillis() + "-" + java.util.UUID.randomUUID() + ".json");
            var data = MAPPER.createObjectNode();
            // метка источника ложится в сам дамп: пересказывает этот разговор уже
            // другой процесс, и без неё чужая личка уехала бы в общий круг
            data.put("source", origin.source().wire());
            data.put("source_chat", origin.chatId());
            data.set("conversation", MAPPER.valueToTree(conversation));
            ru.rainedev.raine.storage.AtomicFiles.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));
            log.info("Разговор отложен до лучшей связи: {}", file.getFileName());
        } catch (IOException | RuntimeException e) {
            // дальше спасать нечем: этот разговор действительно потерян
            log.error("Разговор не удалось отложить", e);
            throw new IllegalStateException("Исходный разговор не удалось сохранить", e);
        }
    }

    public void keepFailure(String notification, List<Message> context, String reason) {
        try {
            Path file = dir.resolve("failed-notifications").resolve(System.currentTimeMillis()
                    + "-" + java.util.UUID.randomUUID() + ".json");
            var data = MAPPER.createObjectNode();
            data.put("notification", notification);
            data.put("reason", reason);
            data.set("context", MAPPER.valueToTree(context));
            ru.rainedev.raine.storage.AtomicFiles.writeString(file,
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(data));
            log.warn("Необработанное уведомление сохранено: {}", file);
        } catch (IOException failure) {
            throw new java.io.UncheckedIOException("Не удалось сохранить уведомление", failure);
        }
    }

    /** @return отложенное, самое давнее первым */
    public List<Path> waiting() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            log.warn("Не удалось заглянуть в отложенные разговоры: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Уведомления, оставшиеся без ответа. В отличие от отложенных разговоров их
     * никто сам не пересказывает: в Telegram такое сообщение уже прочитано, и
     * при следующем запуске оно не всплывёт. Оно лежит здесь только для глаз
     * человека.
     *
     * @return файлы неотвеченных, самый давний первым
     */
    public List<Path> unanswered() {
        Path failures = dir.resolve("failed-notifications");
        if (!Files.isDirectory(failures)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(failures)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();
        } catch (IOException e) {
            log.warn("Не удалось заглянуть в неотвеченные: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Неотвеченные уведомления нужны для разбора считанные дни; без чистки
     * папка data/pending/failed-notifications растёт вечно. Срок считается по
     * времени изменения: файл могли перезаписать уже после сбоя.
     *
     * @return сколько файлов убрано
     */
    public int pruneFailures(java.time.Duration age) {
        Path failures = dir.resolve("failed-notifications");
        if (!Files.isDirectory(failures)) {
            return 0;
        }
        long before = System.currentTimeMillis() - age.toMillis();
        int removed = 0;
        try (Stream<Path> files = Files.list(failures)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".json")).toList()) {
                try {
                    if (Files.getLastModifiedTime(file).toMillis() < before) {
                        Files.deleteIfExists(file);
                        removed++;
                    }
                } catch (IOException e) {
                    log.warn("Неотвеченное {} не удалилось: {}", file.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Не удалось почистить неотвеченные: {}", e.getMessage());
        }
        if (removed > 0) {
            log.info("Убрано старых неотвеченных уведомлений: {}", removed);
        }
        return removed;
    }

    public Pending read(Path file) {
        try {
            JsonNode root = MAPPER.readTree(file.toFile());
            // прежние дампы — голый список сообщений: метки в них нет, и
            // разговор остаётся общим, но читается
            boolean legacy = root.isArray();
            JsonNode messages = legacy ? root : root.path("conversation");
            Origin origin = legacy ? Origin.GENERAL
                    : new Origin(root.path("source_chat").asLong(0), Source.of(root.path("source").asText("")));
            return new Pending(origin, MAPPER.convertValue(messages, new TypeReference<List<Message>>() {}));
        } catch (IOException | RuntimeException e) {
            log.warn("Отложенный разговор {} не читается: {}", file.getFileName(), e.getMessage());
            return new Pending(Origin.GENERAL, List.of());
        }
    }

    /** Разобранное убираем: иначе оно будет пересказываться каждый запуск. */
    public void done(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Отложенный разговор {} не удалился: {}", file.getFileName(), e.getMessage());
        }
    }
}
