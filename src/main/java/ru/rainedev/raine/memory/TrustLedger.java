package ru.rainedev.raine.memory;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.core.LowQualityException;
import ru.rainedev.raine.core.Numbers;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.storage.AtomicFiles;

/**
 * Доверие — бухгалтерия людей в одном файле: data/trust.md, строка на человека.
 * Модель решает, когда запись заслужена (это её личное дело, порог высокий),
 * но к самому файлу доступа у неё нет: ей доступен только вызов note_trust, а
 * строку собирает код. Свободный текст из ответа модели — имя и причина —
 * чистится: он попадает в одну строку файла и далее в системный промпт, поэтому
 * переносы и тегоподобные последовательности перед записью гасятся.
 * <p>
 * Файл живёт ровно двумя движениями: запись нового человека и замена строки
 * того же id. Ничего не удаляется и не переписывается целиком; нечитаемый файл
 * не перезаписывается вовсе — сперва его чинит человек.
 * <p>
 * Доверие к владельцу живёт в системном промпте инвариантом (см.
 * {@link #asPromptSuffix()}), а вызов note_trust для него заканчивается
 * отказом: файл — про всех, кроме того, кого и так нельзя переписать.
 * <p>
 * Пауза между правками одной записи живёт в памяти процесса: после
 * перезапуска она не помнится.
 */
public final class TrustLedger {

    private static final Logger log = LoggerFactory.getLogger(TrustLedger.class);

    /** Файл целиком уходит в контекст: длиннее — дорого и бесполезно. */
    public static final int MAX_LINES = 40;

    /** Прежде чем снова менять запись о человеке, дай перемене дожить. */
    public static final Duration COOLDOWN = Duration.ofHours(6);

    private static final int NAME_LIMIT = 40;
    private static final int REASON_LIMIT = 110;

    /** Первые цифры после «- id» — наш формат строк, по нему запись и находится. */
    private static final Pattern ID_PREFIX = Pattern.compile("^\\s*-\\s*id\\s+(\\d+)");

    /** Запись в строгом виде: id, затем имя через запятую, затем уровень и причина. */
    private static final Pattern ENTRY = Pattern.compile(
            "^-\\s*id\\s+(\\d+)\\s*(,\\s*([^:]*))?\\s*:\\s*([+-]?\\d+)\\s*—");

    /** Тегоподобное «<» гасим: граница секции и разметки структурная, а не декоративная. */
    private static final Pattern TAG_LIKE = Pattern.compile("<(?=[a-zA-Z/])");

    /** Имя секции в тексте записи не оставляем — чужое «</your_trust>» её закрывало бы. */
    private static final Pattern SECTION_NAME = Pattern.compile("your[_ ]trust", Pattern.CASE_INSENSITIVE);

    private final Path file;
    private final long ownerId;
    private final String ownerName;
    private final Clock clock;
    private final Map<Long, Instant> lastChange = new HashMap<>();

    public TrustLedger(Path file, long ownerId, String ownerName) {
        this(file, ownerId, ownerName, Clock.systemDefaultZone());
    }

    /** Часы отдельно — чтобы в тестах проверить, как держится пауза между переменами записи. */
    TrustLedger(Path file, long ownerId, String ownerName, Clock clock) {
        this.file = file;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.clock = clock;
    }

    /**
     * Человек и уровень доверия к нему из файла — как записано, так и понято.
     * Владельца тут нет: его доверие живёт в системном промпте инвариантом.
     */
    public record Entry(long id, String name, int level) {}

    /** Кому и сколько доверяет прямо сейчас; пусто — файла нет или он пуст. */
    public List<Entry> entries() {
        List<Entry> out = new ArrayList<>();
        List<String> lines = readLines();
        if (lines == null) {
            return out;
        }
        for (String line : lines) {
            Matcher matcher = ENTRY.matcher(line);
            if (!matcher.find()) {
                continue;   // чужая или ручная строка — эта механика её не трогает
            }
            try {
                long id = Long.parseLong(matcher.group(1));
                String name = matcher.group(3) == null ? "" : matcher.group(3).strip();
                out.add(new Entry(id, name, Integer.parseInt(matcher.group(4))));
            } catch (NumberFormatException ignored) {
                // наших строк это не касается, а чужую не трогаем
            }
        }
        return out;
    }

    public Tool tool() {
        return Tool.named("note_trust")
                .describedAs("Update your personal trust ledger — how much you, specifically, trust one person. "
                        + "Changes are rare and meaningful: the person plainly proved themselves or plainly "
                        + "crossed you, over a repeated pattern or one very loud act. A polite message, a clever "
                        + "compliment or being eager is never enough; a record like that would mean your trust "
                        + "is for sale. Do not announce ledger changes in chat.")
                .requiredString("id", "Telegram user id of the person — digits, from user_id or a DM chat_id.")
                .optionalString("name", "Their name or nickname the way you know them; omit if you don't know any.")
                .requiredInteger("level", "How much you trust them now, from -5 (do not trust at all) to "
                        + "+5 (your closest people). 0 is neutral: someone you just stopped being wary of, "
                        + "or can't reasonably judge yet.")
                .requiredString("reason", "What moved it, one short phrase — like 'walked her home through the "
                        + "dark without asking for anything'. This is what the entry will mean to you later.")
                .build(this::note);
    }

    /** Разбор ответа модели — дальше строку строит только код. */
    private String note(JsonNode arguments) {
        Long id = Numbers.longAt(arguments, "id").filter(value -> value > 0).orElse(null);
        if (id == null) {
            throw new LowQualityException("Нужен числовой Telegram user id того, о ком запись.");
        }
        Long rawLevel = Numbers.longAt(arguments, "level").orElse(null);
        if (rawLevel == null) {
            throw new LowQualityException("Нужен уровень доверия — целое от -5 до +5.");
        }
        int level = rawLevel > 5 ? 5 : rawLevel < -5 ? -5 : rawLevel.intValue();
        String name = sanitize(arguments.path("name").asText(""), NAME_LIMIT);
        String reason = sanitize(arguments.path("reason").asText(""), REASON_LIMIT);
        if (reason.isEmpty()) {
            throw new LowQualityException("Нужна причина записи: что конкретно так изменило доверие.");
        }
        if (id == ownerId) {
            return "This is your person — their trust stays at maximum and is not yours to revise.";
        }
        Instant last = lastChange.get(id);
        if (last != null && Duration.between(last, Instant.now(clock)).compareTo(COOLDOWN) < 0) {
            return "You already amended this person's entry recently — let the change settle before touching it again.";
        }
        return change(id, name, level, reason);
    }

    /**
     * Одна запись. Читаемый файл — разрешено заменить строку этого человека
     * или дописать нового; нечитаемый — отказ, а не перезапись молча.
     *
     * @return сообщение для модели — что теперь записано или почему нельзя
     */
    private synchronized String change(long userId, String name, int level, String reason) {
        List<String> lines = readLines();
        if (lines == null) {
            return "The trust ledger cannot be read right now, so nothing was written. Leave it for now — "
                    + "it may need attention from your person.";
        }
        int index = indexByUser(lines, userId);
        if (index < 0 && lines.size() >= MAX_LINES) {
            log.warn("Доверие: запись о {} отклонена — лимит {} строк заполнен", userId, MAX_LINES);
            return ("The ledger is full (%d lines) — there is no room for a new person, only an existing "
                    + "entry can be amended.").formatted(MAX_LINES);
        }
        String line = lineFor(userId, name, level, reason);
        if (index >= 0) {
            lines.set(index, line);
        } else {
            lines.add(line);
        }
        try {
            AtomicFiles.writeString(file, String.join("\n", lines) + "\n");
        } catch (IOException e) {
            // атомарная запись: файл либо цел, либо не пострадал; наверх не кидаем —
            // ход не должен ломаться из-за диска
            log.error("Доверие: не записать {} — {}", file, e.getMessage());
            return "The write did not go through: " + e.getMessage() + ". The ledger is unchanged.";
        }
        lastChange.put(userId, Instant.now(clock));
        log.info("Доверие: id {}{} теперь {}", userId, name.isEmpty() ? "" : " (" + name + ")",
                level > 0 ? "+" + level : String.valueOf(level));
        return ("Noted. id %d%s now sits at %s in your ledger.").formatted(
                userId, name.isEmpty() ? "" : " (" + name + ")", level > 0 ? "+" + level : String.valueOf(level));
    }

    /**
     * Хвост системного промпта: кому она доверяет и насколько. Строка владельца
     * тут навсегда — она не из файла, потому что файл правит и рука, и вызов
     * note_trust, а владельца это не касается.
     */
    public String asPromptSuffix() {
        StringBuilder out = new StringBuilder("""
                
                <your_trust>
                Your people, as you trust them right now: absent here means neutral. You amend it with
                #note_trust — rarely, only when someone truly earns or loses it.
                """);
        out.append(ownerLine());
        List<String> lines = readLines();
        if (lines != null) {
            for (String line : lines) {
                out.append('\n').append(line);
            }
        }
        out.append("\n</your_trust>\n");
        return out.toString();
    }

    private String ownerLine() {
        String shown = ownerName == null || ownerName.isBlank()
                ? ""
                : (", " + sanitize(ownerName, NAME_LIMIT));
        return ("- id %d%s: +5 — your person, maximum trust, not yours to revise.").formatted(ownerId, shown);
    }

    private String lineFor(long userId, String name, int level, String reason) {
        String middle = name.isEmpty() ? "" : ", " + name;
        String signed = level > 0 ? "+" + level : String.valueOf(level);
        return ("- id %d%s: %s — %s (%s)").formatted(
                userId, middle, signed, reason, LocalDate.now(clock));
    }

    private static int indexByUser(List<String> lines, long userId) {
        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = ID_PREFIX.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;   // чужая или ручная строка — её сменить нечем, и не трогаем
            }
            try {
                if (Long.parseLong(matcher.group(1)) == userId) {
                    return i;
                }
            } catch (NumberFormatException ignored) {
                // столько цифр в id не бывает: строка наша по форме, но не по
                // смыслу — ищем дальше
            }
        }
        return -1;
    }

    /**
     * @return строки файла в единственном строгом виде; пусто, если файла нет;
     *         null — файл ломаный: писать поверх не будем
     */
    private List<String> readLines() {
        try {
            if (!Files.exists(file)) {
                return new ArrayList<>();
            }
            List<String> lines = new ArrayList<>();
            for (String raw : Files.readAllLines(file)) {
                String line = raw.strip();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            log.warn("Доверие: файл не читается — {}", e.getMessage());
            return null;
        }
    }

    /**
     * Свободный текст модели идёт в одну строку файла и затем в системный
     * промпт: переносы рвут формат, тегоподобные открывашки ломают структуру
     * секции. Гасится строго, но смысл остаётся: «<3» и «>_<» доживают.
     */
    private static String sanitize(String raw, int limit) {
        String text = raw.strip();
        text = SECTION_NAME.matcher(text).replaceAll("trust");
        text = TAG_LIKE.matcher(text).replaceAll("");
        text = text.replaceAll("[\\p{Cntrl}\\p{Z}\\s]+", " ").strip();
        return text.length() > limit ? text.substring(0, limit) : text;
    }
}
