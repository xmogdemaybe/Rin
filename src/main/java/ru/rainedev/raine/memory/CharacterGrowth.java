package ru.rainedev.raine.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;

/**
 * Рост личности. Характер задаётся фундаментом, а поверх него ночами наживается
 * подтверждённое: днём модель оставляет о себе заметки (record_trait_signal),
 * они лежат в метаданных записей дневника рядом с эмбеддингами, а этот проход
 * пересматривает их и переписывает ровно один файл — prompts/character_growth.md.
 * <p>
 * Дневник проход не трогает вовсе: не пишет, не двигает, не удаляет. Ответ
 * пустой, похожий на вызовы инструментов или длиннее лимита — шаг пропускается
 * целиком: старый файл лучше свежей отсебятины. О переполнении лимита слышит
 * не только журнал, но и владелец: файл тогда пора разбирать руками. Бэкапов
 * не делается — файл и так переписывается целиком за одну ночь.
 */
public final class CharacterGrowth {

    private static final Logger log = LoggerFactory.getLogger(CharacterGrowth.class);

    /** Файл каждый ход уходит в контекст: длиннее — дорого. */
    public static final int MAX_LINES = 40;

    /** Повтор — это не два замечания в один вечер, а разнесённые по времени. */
    public static final Duration CORROBORATION_SPAN = Duration.ofHours(12);

    /** Одиночная, но такая сильная, что это вспышка: идёт в файл и без повтора. */
    public static final double FLASHBULB = 0.9;

    /** Заметки старше этого шанса подтвердиться уже не имеют — они не перечитываются. */
    public static final Duration STALE = Duration.ofDays(14);

    /** Набор повторов одной черты: все заметки с одной формулировкой. */
    private record Group(TraitSignal first, List<TraitSignal> signals, double intensity) {}

    private final Diary diary;
    private final LlmClient llm;
    private final Supplier<String> prompt;
    private final Path file;
    private final Clock clock;

    /**
     * Кому сказать, что файл упёрся в лимит, и сколько строк было в ответе.
     * По умолчанию некому — отказ остаётся только в журнале.
     */
    private volatile LongConsumer overflowAlert = lines -> { };

    /** Один отказ на случай: сказать раз, а не каждую ночь, пока файл не разберут. */
    private volatile boolean overflowTold;

    public CharacterGrowth(Diary diary, LlmClient llm, Supplier<String> prompt, Path file) {
        this(diary, llm, prompt, file, Clock.systemDefaultZone());
    }

    /** Часы отдельно — в тестах видно, как заметки стареют, не дожидаясь календаря. */
    CharacterGrowth(Diary diary, LlmClient llm, Supplier<String> prompt, Path file, Clock clock) {
        this.diary = diary;
        this.llm = llm;
        this.prompt = prompt;
        this.file = file;
        this.clock = clock;
    }

    /** Куда сообщить о переполнении: владелец должен знать, что файл пора разбирать руками. */
    public void onOverflow(LongConsumer alert) {
        this.overflowAlert = alert;
    }

    /**
     * Один ночной проход: собрал свежие заметки → отобрал подтверждённое →
     * один запрос модели → проверила ответ → перезаписала файл.
     *
     * @param interrupted проснулась или рост выключили на ходу — уходить сразу
     * @return перезаписан ли файл
     */
    public boolean pass(BooleanSupplier interrupted) {
        if (interrupted.getAsBoolean()) {
            return false;
        }
        List<TraitSignal> recent = recentSignals();
        List<Group> worthwhile = worthwhile(recent);
        if (worthwhile.isEmpty()) {
            log.info("Рост личности: подтверждённых черт пока нет — заметки ждут повтора");
            return false;
        }
        String instructions = prompt.get();
        if (instructions.isBlank()) {
            log.warn("Рост личности: шаблон character_growth_pass.md пуст — шаг пропущен");
            return false;
        }
        if (interrupted.getAsBoolean()) {
            return false;
        }
        String content;
        try {
            content = llm.chat(instructions, List.of(Message.user(request(worthwhile))), null).text();
        } catch (RuntimeException e) {
            log.warn("Рост личности: проход сорвался — {}", e.getMessage());
            return false;
        }
        return accept(content);
    }

    /** Отсекает пустой ответ, разметку вызова инструмента и разросшийся файл. */
    private boolean accept(String content) {
        String answer = content == null ? "" : content.strip();
        if (answer.isEmpty() || ModelText.looksLikeToolCall(answer)) {
            log.warn("Рост личности: ответ пуст или похож на вызов инструмента — файл не тронут");
            return false;
        }
        long lines = answer.lines().count();
        if (lines > MAX_LINES) {
            log.warn("Рост личности: в ответе {} строк при лимите {} — файл не перезаписан",
                    lines, MAX_LINES);
            tellAboutOverflow(lines);
            return false;
        }
        try {
            ru.rainedev.raine.storage.AtomicFiles.writeString(file, answer + "\n");
        } catch (IOException e) {
            // атомарная запись: старый файл либо цел, либо не пострадал. Ошибка
            // остаётся здесь — ночной сон не должен падать из-за диска
            log.error("Рост личности: не перезаписать {} — {}", file, e.getMessage());
            return false;
        }
        overflowTold = false;
        log.info("Рост личности: файл перезаписан, {} строк", lines);
        return true;
    }

    /** Владелец слышит о переполнении один раз: дальше его очередь, а не её. */
    private void tellAboutOverflow(long lines) {
        if (overflowTold) {
            return;
        }
        overflowTold = true;
        overflowAlert.accept(lines);
    }

    /** Свежая заметка: не просрочена и не потеряла метку времени. */
    private List<TraitSignal> recentSignals() {
        List<TraitSignal> recent = new ArrayList<>();
        Instant horizon = Instant.now(clock).minus(STALE);
        for (Diary.Match match : diary.query(new double[0])) {
            for (TraitSignal signal : match.entry().metadata().signals()) {
                if (signal.at().isBlank()) {
                    continue;
                }
                try {
                    if (Instant.parse(signal.at()).isBefore(horizon)) {
                        continue;
                    }
                } catch (RuntimeException ignored) {
                    // метка не разобралась — заметка всё равно свежая на вид
                }
                recent.add(signal);
            }
        }
        return recent;
    }

    /** Отбирает подтверждённое: повтор через приличный срок или одиночная вспышка. */
    private List<Group> worthwhile(List<TraitSignal> signals) {
        List<Group> groups = new ArrayList<>();
        for (TraitSignal signal : signals) {
            int found = -1;
            for (int i = 0; i < groups.size(); i++) {
                if (groups.get(i).first().kind().strip().equalsIgnoreCase(signal.kind().strip())
                        && groups.get(i).first().trait().strip().equalsIgnoreCase(signal.trait().strip())) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                groups.add(new Group(signal, new ArrayList<>(List.of(signal)), signal.intensity()));
            } else {
                Group group = groups.remove(found);
                List<TraitSignal> members = new ArrayList<>(group.signals());
                members.add(signal);
                groups.add(found, new Group(group.first(), members,
                        Math.max(group.intensity(), signal.intensity())));
            }
        }
        return groups.stream().filter(group -> confirmed(group.signals())).toList();
    }

    /** Повтор — когда замечания разнесены по времени, а не идут подряд один за другим. */
    private static boolean confirmed(List<TraitSignal> members) {
        for (TraitSignal signal : members) {
            if (signal.intensity() >= FLASHBULB) {
                return true;
            }
        }
        if (members.size() < 2) {
            return false;
        }
        List<Instant> moments = new ArrayList<>();
        for (TraitSignal signal : members) {
            try {
                moments.add(Instant.parse(signal.at()));
            } catch (RuntimeException ignored) {
                // внятного времени нет — верим на слово
                return true;
            }
        }
        moments.sort(Instant::compareTo);
        return Duration.between(moments.getFirst(), moments.getLast()).compareTo(CORROBORATION_SPAN) >= 0;
    }

    /** Вопрос модели: что нажито и что показалось заново. */
    private String request(List<Group> groups) {
        StringBuilder text = new StringBuilder();
        text.append("<current_growth>\n");
        String current = current();
        text.append(current.isBlank() ? "(пусто — ничего нажитого ещё нет)" : current.strip());
        text.append("\n</current_growth>\n\n<signals>\n");
        for (Group group : groups) {
            TraitSignal last = group.signals().getLast();
            text.append("- ").append(group.first().trait().strip())
                    .append("; kind: ").append(group.first().kind().strip())
                    .append("; valence: ").append(String.format("%+.1f", group.first().valence()))
                    .append("; intensity: ").append(String.format("%.1f", group.intensity()))
                    .append("; subject: ")
                    .append(group.first().subject().strip())
                    .append("; noted: ").append(group.signals().size()).append(" раз")
                    .append("; first: ").append(group.first().at())
                    .append("; last: ").append(last.at())
                    .append('\n');
        }
        text.append("</signals>");
        return text.toString();
    }

    private String current() {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            return Files.readString(file);
        } catch (IOException e) {
            log.warn("Рост личности: не читается {} — {}", file, e.getMessage());
            return "";
        }
    }
}
