package ru.rainedev.raine.speech;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Refusal;
import ru.rainedev.raine.prompt.Prompts;

/**
 * Слух самой модели.
 * <p>
 * Разговорная модель слышит звук с завода: ей можно положить в запрос голосовое
 * и попросить пересказать сказанное — отдельный распознаватель для этого не
 * нужен. Здесь именно этот путь, и он не зависит от зрения: голосовое можно
 * слушать, даже когда картинок она не видит вовсе.
 * <p>
 * Telegram распознаёт речь сам, но только с премиума. Без него голосовые и
 * кружки оставались словами «[голосовое]» — видно, что было, а что сказали,
 * неизвестно.
 */
public final class ModelHearing {

    private static final Logger log = LoggerFactory.getLogger(ModelHearing.class);

    /** Пустой файл Telegram отдаёт, когда вложение ещё не скачалось. */
    private static final int MIN_AUDIO_BYTES = 512;

    /**
     * Больше не отправляют: запрос целиком, с учётом base64, упирается в потолок
     * эндпоинта, а доплатить за него всё равно нельзя.
     */
    private static final int MAX_AUDIO_BYTES = 12_000_000;

    /**
     * Чем был файл: эндпоинт по этому слову решает, как его распаковать.
     * Голосовые Telegram кладёт как OGG/Opus, а из кружка дорожку вынимают в
     * ту же упаковку — на слух они неразличимы, и просить распаковщик видео
     * незачем: в разговорном формате его всё равно нет.
     */
    private static final String AUDIO_FORMAT = "ogg";

    private final LlmClient llm;
    private final Prompts prompts;
    private final String model;
    private final Path cacheDir;
    private final Config.Hearing config;

    public ModelHearing(LlmClient llm, Prompts prompts, String model, Path cacheDir, Config.Hearing config) {
        this.llm = llm;
        this.prompts = prompts;
        this.model = model;
        this.cacheDir = cacheDir;
        this.config = config;
    }

    /** Голосовые. */
    public boolean hearsVoiceNotes() {
        return config.voiceNotes() && llm.hears();
    }

    /** Видеокружки. */
    public boolean hearsVideoNotes() {
        return config.videoNotes() && llm.hears();
    }

    /**
     * @param file   голосовое или кружок, как он лежит в каталоге Telegram: по его
     *               имени узнаётся то, что уже слушали
     * @param circle кружок вместо голосового: это видео, и звук из него вынимают
     * @return сказанное словами или пусто, если не вышло
     */
    public Optional<String> listen(Path file, boolean circle) {
        String remembered = fromCache(file);
        if (remembered != null) {
            return Optional.of(remembered);
        }
        Optional<byte[]> sound = soundOf(file, circle);
        if (sound.isEmpty()) {
            return Optional.empty();
        }
        byte[] audio = sound.get();
        if (audio.length < MIN_AUDIO_BYTES) {
            log.info("Файл {} пуст ({} байт) — слушать нечего", file.getFileName(), audio.length);
            return Optional.empty();
        }
        if (audio.length > MAX_AUDIO_BYTES) {
            // целое интервью в одно сообщение не кладут: запрос целиком в
            // 20 мегабайт эндпоинт не примет
            log.info("Запись {} слишком длинная, чтобы слушать её целиком ({} байт)",
                    file.getFileName(), audio.length);
            return Optional.empty();
        }
        try {
            String heard = llm.hear(model, prompts.load("voice_to_text.md"),
                    circle ? "This is a video message circle: only the words matter, do not describe the picture."
                            : "This is a voice message.",
                    audio, AUDIO_FORMAT);
            if (Refusal.detected(heard)) {
                // в кэш не кладём: иначе извинение станет вечной расшифровкой
                log.info("Слушать отказались: {}", firstLine(heard));
                return Optional.empty();
            }
            String said = heard == null ? "" : heard.strip();
            if (said.isEmpty() || silence(said)) {
                log.debug("В {} слов не услышано", file.getFileName());
                return Optional.empty();
            }
            toCache(file, said);
            log.info("Услышано: {}", firstLine(said));
            return Optional.of(said);
        } catch (RuntimeException e) {
            log.warn("Прослушать {} не вышло: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Что слушать: сам файл или дорожку из него.
     * <p>
     * Кружок — это видео, а видео в запрос на слух не кладут: разговорная модель
     * принимает только звук. Дорожка вынимается своими силами и в той же упаковке,
     * что и голосовое, — поэтому слушают их одинаково.
     */
    private Optional<byte[]> soundOf(Path file, boolean circle) {
        if (!circle) {
            return read(file);
        }
        Optional<Path> track = VideoAudio.extract(file);
        if (track.isEmpty()) {
            log.debug("Из кружка {} дорожка не досталась — останется неслышанным", file.getFileName());
            return Optional.empty();
        }
        try {
            return read(track.get());
        } finally {
            try {
                Files.deleteIfExists(track.get());
            } catch (IOException e) {
                log.debug("Временная дорожка не удалилась: {}", e.getMessage());
            }
        }
    }

    private Optional<byte[]> read(Path file) {
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Молчание модель описывает одним словом, и без этого правила оно ушло бы
     * в разговор как сказанное. Видно пакету: проверять можно без эндпоинта.
     */
    static boolean silence(String heard) {
        return heard.replaceAll("[^\\p{L}]", "").equalsIgnoreCase("silence");
    }

    private static String firstLine(String text) {
        return text == null ? "" : text.strip().lines().findFirst().orElse("");
    }

    /** Одна и та же запись прилетает не раз: чат открывают снова и снова. */
    private String fromCache(Path file) {
        try {
            Path cached = cacheFor(file);
            return Files.exists(cached) ? Files.readString(cached) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void toCache(Path file, String heard) {
        try {
            Files.createDirectories(cacheDir);
            Files.writeString(cacheFor(file), heard);
        } catch (IOException e) {
            log.debug("Услышанное не сохранилось в кэш: {}", e.getMessage());
        }
    }

    private Path cacheFor(Path file) {
        return cacheDir.resolve(file.getFileName() + ".heard.md");
    }
}
