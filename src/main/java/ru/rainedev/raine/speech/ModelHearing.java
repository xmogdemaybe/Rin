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
     * Сколько секунд записи слушаем.
     * <p>
     * wav без сжатия — два байта на отсчёт: девяносто секунд речи с 16 кГц — это
     * около трёх мегабайт, а в base64 — четыре мегабайта тела запроса при таймауте
     * в три минуты. Раньше бюджет стоял в 300 с, и запись длиннее выбрасывалась
     * целиком: шестиминутное голосовое доходило до модели пустым местом. Длинное
     * теперь обрезают до бюджета — начало слышно, и его не выдают за целое.
     */
    private static final int MAX_AUDIO_SECONDS = 90;

    /** Пометка о том, что дослушано не до конца — её читает модель, поэтому она английская. */
    private static final String CUT_NOTE = "[the recording is longer than %d s — only its beginning was heard]";

    /**
     * Чем был файл: эндпоинт по этому слову решает, как его распаковать.
     * Голосовые Telegram кладёт как OGG/Opus, но разговорные эндпоинты признают
     * в запросе только wav и mp3 — поэтому звук раскодирован и уложен в wav,
     * а дорожка кружка доходит до него тем же путём.
     */
    private static final String AUDIO_FORMAT = "wav";

    /**
     * Столько не переспрашиваем эндпоинт об одном и том же файле.
     * <p>
     * Одна и та же запись прилетает не раз: чат открывают снова, и история
     * перерисовывается. Неудачное прослушивание в кэш не ложится — иначе отказ
     * стал бы вечной «расшифровкой», — а без этой метки за каждый повтор
     * приходилось бы платить заново.
     */
    private static final long RETRY_AFTER_MILLIS = 15 * 60 * 1000;

    /** Столько файлов отметки переживают спокойно: больше — только за месяц работы. */
    private static final int PAID_LIMIT = 4096;

    /**
     * Что именно слушают: голосовое, дорожку кружка или дорожку обычного ролика.
     * <p>
     * Одно слово решает и распаковку, и разрешение слушать вовсе. Раньше за
     * разрешение отвечал тот, кто зовёт, — а к кружку вели две дороги, и на
     * второй, через зрение, спрашивалось разрешение ролика.
     */
    public enum Track {
        /** Голосовое сообщение. */
        VOICE,
        /** Видеокружок: картинка и дорожка в одном файле. */
        CIRCLE,
        /** Ролик, присланный видео или файлом. */
        VIDEO;

        /** Звук вынимают из видеодорожки — это и кружок, и обычный ролик. */
        boolean fromVideo() {
            return this != VOICE;
        }
    }

    private final LlmClient llm;
    private final Prompts prompts;
    private final String model;
    private final Path cacheDir;
    private final Config.Hearing config;

    /** Когда за файл уже платили. Растёт вместе с числом вложений за запуск. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> paid
            = new java.util.concurrent.ConcurrentHashMap<>();

    public ModelHearing(LlmClient llm, Prompts prompts, String model, Path cacheDir, Config.Hearing config) {
        this.llm = llm;
        this.prompts = prompts;
        this.model = model;
        this.cacheDir = cacheDir;
        this.config = config;
    }

    /**
     * Слышит ли это вложение вовсе: общий слух, тумблер владельца и то, принимает
     * ли модель звук.
     * <p>
     * Спрашивается по типу дорожки, а не тремя разными вопросами: кружок умел
     * получить разрешение ролика, и снятый тумблер оставался неисполненным.
     */
    public boolean hears(Track track) {
        return switch (track) {
            case VOICE -> hearsVoiceNotes();
            case CIRCLE -> hearsVideoNotes();
            case VIDEO -> hearsVideoAudio();
        };
    }

    /** Голосовые. */
    boolean hearsVoiceNotes() {
        return config.enabled() && config.voiceNotes() && llm.hears();
    }

    /** Видеокружки. */
    boolean hearsVideoNotes() {
        return config.enabled() && config.videoNotes() && llm.hears();
    }

    /**
     * Звук из обычных роликов: картинку смотрит зрение, а слова из дорожки берёт
     * та же модель, что говорит. Отдельного распознавателя для этого не нужно —
     * и без рабочего адреса на нём бот делал бы вид, что слушает.
     */
    boolean hearsVideoAudio() {
        return config.enabled() && llm.hears();
    }

    /**
     * @param file  голосовое или ролик, как он лежит в каталоге Telegram: по его
     *              имени узнаётся то, что уже слушали
     * @param track чем является звук: от него зависят и разрешение слушать,
     *              и распаковка, и подсказка эндпоинту
     * @return сказанное словами или пусто, если не вышло
     */
    public Optional<String> listen(Path file, Track track) {
        if (!hears(track)) {
            // разрешение держится здесь, а не у того, кто зовёт: к кружку вели
            // две дороги, и на одной из них оно терялось
            return Optional.empty();
        }
        String remembered = fromCache(file);
        if (remembered != null) {
            return Optional.of(remembered);
        }
        Optional<Take> take = soundOf(file, track);
        if (take.isEmpty()) {
            // эндпоинт ещё не спрошен, и пауза здесь не нужна: Telegram отдаёт
            // вложение по частям, и за недокачанный файл платить нечем
            return Optional.empty();
        }
        if (!claim(file)) {
            return Optional.empty();
        }
        try {
            String heard = llm.hear(model, prompts.load("voice_to_text.md"),
                    track.fromVideo() ? "This is the sound track of a video: only the words matter, do not describe the picture."
                            : "This is a voice message.",
                    take.get().audio(), AUDIO_FORMAT);
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
            // пометка об обрезке ложится и в кэш: перечитанное позже должно
            // оставаться таким же неполным, каким оно услышано
            String words = withNote(said, take.get().cut());
            toCache(file, words);
            log.info("Услышано: {}", firstLine(words));
            return Optional.of(words);
        } catch (RuntimeException e) {
            log.warn("Прослушать {} не вышло: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Что слушать: речь из голосового или дорожку из ролика — и то, и другое
     * доводится до wav.
     * <p>
     * Ролик — это видео, а видео в запрос на слух не кладут: разговорная модель
     * принимает только звук. Голосовое же принимается как звук, но в упаковке,
     * которую эндпоинты не знают, — его раскодированием занимаются здесь.
     * <p>
     * Бюджет уходит распаковщикам до тяжёлой работы, и спрашивают его один раз:
     * проверка после декодирования значила бы, что дорогую работу уже оплатили,
     * чтобы потом её выбросить.
     */
    private Optional<Take> soundOf(Path file, Track track) {
        Optional<Sound> sound = track.fromVideo() ? VideoAudio.sound(file, MAX_AUDIO_SECONDS) : voiceOf(file);
        if (sound.isEmpty()) {
            if (track.fromVideo()) {
                log.debug("Из ролика {} дорожка не досталась — останется неслышанным", file.getFileName());
            }
            return Optional.empty();
        }
        Sound raw = sound.get();
        return Optional.of(new Take(Wav.of(raw.samples(), raw.rate()), raw.cut()));
    }

    /** Звук, готовый к запросу, и метка о том, что его обрезали по бюджету. */
    private record Take(byte[] audio, boolean cut) {}

    private Optional<Sound> voiceOf(Path file) {
        Optional<byte[]> bytes = read(file);
        if (bytes.isEmpty()) {
            return Optional.empty();
        }
        if (bytes.get().length < MIN_AUDIO_BYTES) {
            log.info("Файл {} пуст ({} байт) — слушать нечего", file.getFileName(), bytes.get().length);
            return Optional.empty();
        }
        return OggOpusReader.decode(bytes.get(), MAX_AUDIO_SECONDS);
    }

    private Optional<byte[]> read(Path file) {
        try {
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.warn("Не удалось прочитать {}: {}", file.getFileName(), e.getMessage());
            return Optional.empty();
        }
    }

    private static String key(Path file) {
        return file.getFileName().toString();
    }

    /**
     * Занимает файл под прослушивание: отказывает, если за него платили меньше
     * четверти часа назад.
     * <p>
     * Занятие, а не проверка с последующей отметкой: одно и то же голосовое
     * разбирают два потока в одну секунду, и между «ещё не платили» и отметкой
     * успевали оба — эндпоинт платил дважды за один и тот же отказ. Отметка
     * ставится до запроса, потому что неудача в кэш не ложится и без неё за
     * каждую перерисовку чата пришлось бы платить заново.
     * <p>
     * Берётся занятие уже с готовым звуком: недокачанное вложение Telegram
     * докачивает за секунды, а переспросить его после занятия можно было бы
     * только через четверть часа.
     */
    private boolean claim(Path file) {
        long now = System.currentTimeMillis();
        String key = key(file);
        Long before = paid.putIfAbsent(key, now);
        if (before == null) {
            // отметки прожитых нужны только для паузы между попытками: иначе карта
            // росла бы всё время, пока бот не перезапустят
            if (paid.size() > PAID_LIMIT) {
                paid.values().removeIf(at -> now - at >= RETRY_AFTER_MILLIS);
            }
            return true;
        }
        // срок вышел: переспросить дозволяется одному, и право на это выигрывает
        // тот, кому удалась замена
        if (now - before >= RETRY_AFTER_MILLIS && paid.replace(key, before, now)) {
            return true;
        }
        log.debug("К {} уже прислушивались — не платим второй раз", file.getFileName());
        return false;
    }

    /**
     * Молчание модель описывает одним словом, и без этого правила оно ушло бы
     * в разговор как сказанное. Видно пакету: проверять можно без эндпоинта.
     */
    static boolean silence(String heard) {
        return heard.replaceAll("[^\\p{L}]", "").equalsIgnoreCase("silence");
    }

    /**
     * Что отдано услышанным: слова и, если запись обрезана, явная пометка об
     * этом. Молча отдать начало часового монолога значит выдать обрыв за целое,
     * и модель стала бы досказывать речь сама.
     * <p>
     * Видно пакету: собирать это учат без эндпоинта и без длинной записи.
     */
    static String withNote(String words, boolean cut) {
        return cut ? words + "\n" + CUT_NOTE.formatted(MAX_AUDIO_SECONDS) : words;
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
