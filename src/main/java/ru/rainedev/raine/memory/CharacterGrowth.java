package ru.rainedev.raine.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.llm.LlmClient;
import ru.rainedev.raine.llm.Message;
import ru.rainedev.raine.phone.Markup;
import ru.rainedev.raine.storage.AtomicFiles;

/**
 * Рост личности. Характер задаётся фундаментом, а поверх него ночами наживается
 * подтверждённое: днём модель оставляет о себе заметки (record_trait_signal),
 * они лежат в метаданных записей дневника и в копилке ещё не пересказанного
 * разговора, а этот проход пересматривает их и переписывает ровно один файл —
 * prompts/character_growth.md.
 * <p>
 * Что считать одной чертой, решает модель: формулировки свободные, и две
 * заметки об одном и том же почти никогда не совпадают дословно. Код только
 * открывает повод — заметки разнесены по времени или одна очень сильная — и
 * запоминает состав, о котором уже спросил, чтобы не платить за него снова.
 * <p>
 * Дневник проход не трогает вовсе: не пишет, не двигает, не удаляет. Ответ
 * пустой, похожий на вызовы инструментов или длиннее лимита — шаг пропускается
 * целиком: старый файл лучше свежей отсебятины. О переполнении лимита слышит
 * не только журнал, но и владелец: файл тогда пора разбирать руками. Бэкапов
 * не делается — файл и так переписывается целиком за одну ночь.
 */
public final class CharacterGrowth {

    private static final Logger log = LoggerFactory.getLogger(CharacterGrowth.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Файл каждый ход уходит в контекст: длиннее — дорого. */
    public static final int MAX_LINES = 40;

    /**
     * Сколько заметок уходит в один вопрос. Ответ не бывает длиннее
     * {@link #MAX_LINES} строк, поэтому и вопрос держится в тех же рамках:
     * за месяцы заметок набирается больше, чем влезает в ответ, файл упирается
     * в лимит — и рост личности молча перестаёт работать, ночь за ночью.
     */
    public static final int MAX_NOTES = 30;

    /** Повтор — это не два замечания в один вечер, а разнесённые по времени. */
    public static final Duration CORROBORATION_SPAN = Duration.ofHours(12);

    /** Одиночная, но такая сильная, что это вспышка: идёт в файл и без повтора. */
    public static final double FLASHBULB = 0.9;

    private final Diary diary;
    private final TraitSignals pocket;
    private final LlmClient llm;
    private final Supplier<String> prompt;
    private final Path file;
    private final Path state;
    private final Clock clock;

    /**
     * Кому сказать, что файл упёрся в лимит, и сколько строк было в ответе.
     * По умолчанию некому — отказ остаётся только в журнале.
     */
    private volatile LongConsumer overflowAlert = lines -> { };

    /** Один отказ на случай: сказать раз, а не каждую ночь, пока файл не разберут. */
    private volatile boolean overflowTold;

    public CharacterGrowth(Diary diary, TraitSignals pocket, LlmClient llm, Supplier<String> prompt,
                           Path file, Path state) {
        this(diary, pocket, llm, prompt, file, state, Clock.systemDefaultZone());
    }

    /** Часы отдельно — в тестах видно, как заметки стареют, не дожидаясь календаря. */
    CharacterGrowth(Diary diary, TraitSignals pocket, LlmClient llm, Supplier<String> prompt,
                    Path file, Path state, Clock clock) {
        this.diary = diary;
        this.pocket = pocket;
        this.llm = llm;
        this.prompt = prompt;
        this.file = file;
        this.state = state;
        this.clock = clock;
    }

    /** Куда сообщить о переполнении: владелец должен знать, что файл пора разбирать руками. */
    public void onOverflow(LongConsumer alert) {
        this.overflowAlert = alert;
    }

    /**
     * Один ночной проход: собрала свежие заметки → проверила, есть ли повод и
     * не спрашивала ли уже об этом → один запрос модели → проверила ответ →
     * перезаписала файл → запомнила, о чём спросила.
     *
     * @param interrupted проснулась или рост выключили на ходу — уходить сразу
     * @return перезаписан ли файл
     */
    public boolean pass(BooleanSupplier interrupted) {
        if (interrupted.getAsBoolean()) {
            return false;
        }
        List<TraitSignal> recent = fresh();
        if (!worthAsking(recent)) {
            log.info("Рост личности: подтверждённых черт пока нет — заметки ждут повтора");
            return false;
        }
        List<TraitSignal> asked = strongest(recent);
        long mark = mark(asked);
        if (mark == readAsked()) {
            // две несвязанные заметки с разницей в полсуток повод открывают, но
            // модель по этому составу уже ответила: платить за него каждую ночь
            // — холостой цикл, а не рост
            log.info("Рост личности: новых заметок с прошлого прогона нет — вопрос не оплачиваю");
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
            content = llm.chat(instructions, List.of(Message.user(request(asked))), null).text();
        } catch (RuntimeException e) {
            // связи не было — вердикта нет, тот же состав спросят снова
            log.warn("Рост личности: проход сорвался — {}", e.getMessage());
            return false;
        }
        String answer = content == null ? "" : content.strip();
        if (answer.isEmpty() || ModelText.looksLikeToolCall(answer)) {
            log.warn("Рост личности: ответ пуст или похож на вызов инструмента — файл не тронут");
            // вердикт получен: «ничего не нажилось» — второй раз за него не платят
            remember(mark);
            return false;
        }
        long lines = answer.lines().count();
        if (lines > MAX_LINES) {
            log.warn("Рост личности: в ответе {} строк при лимите {} — файл не перезаписан",
                    lines, MAX_LINES);
            tellAboutOverflow(lines);
            // отметку не пишем: файл пора разбирать руками, и ночь повторится
            return false;
        }
        try {
            AtomicFiles.writeString(file, answer + "\n");
        } catch (IOException e) {
            // атомарная запись: старый файл либо цел, либо не пострадал. Ошибка
            // остаётся здесь — ночной сон не должен падать из-за диска
            log.error("Рост личности: не перезаписать {} — {}", file, e.getMessage());
            return false;
        }
        overflowTold = false;
        log.info("Рост личности: файл перезаписан, {} строк", lines);
        remember(mark);
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

    /**
     * Свежие заметки из обоих мест: дневник хранит то, что уже пересказано,
     * копилка — то, что ещё ждёт пересказа. Одна и та же заметка может лежать
     * в обоих (пересказ прошёл, а копилку вернули), поэтому считается один раз.
     */
    private List<TraitSignal> fresh() {
        Instant horizon = Instant.now(clock).minus(TraitSignal.KEEP);
        Map<String, TraitSignal> byKey = new LinkedHashMap<>();
        List<TraitSignal> all = new ArrayList<>(diarySignals());
        all.addAll(pocket.snapshot());
        for (TraitSignal signal : all) {
            if (signal.at().isBlank()) {
                continue;
            }
            Optional<Instant> at = moment(signal);
            if (at.isPresent() && at.get().isBefore(horizon)) {
                continue;
            }
            byKey.putIfAbsent(signal.key(), signal);
        }
        return List.copyOf(byKey.values());
    }

    private List<TraitSignal> diarySignals() {
        List<TraitSignal> signals = new ArrayList<>();
        for (Diary.Match match : diary.query(new double[0])) {
            signals.addAll(match.entry().metadata().signals());
        }
        return signals;
    }

    /**
     * Повод спросить: вспышка сама по себе, либо заметки разнесены по времени.
     * Про одну ли они черту, здесь не решается — формулировки свободные, и
     * дословное сравнение резало бы именно то, ради что заметки пишутся.
     */
    private static boolean worthAsking(List<TraitSignal> signals) {
        for (TraitSignal signal : signals) {
            if (signal.intensity() >= FLASHBULB) {
                return true;
            }
        }
        List<Instant> moments = new ArrayList<>();
        for (TraitSignal signal : signals) {
            moment(signal).ifPresent(moments::add);
        }
        if (moments.size() < 2) {
            return false;
        }
        moments.sort(Instant::compareTo);
        return Duration.between(moments.getFirst(), moments.getLast()).compareTo(CORROBORATION_SPAN) >= 0;
    }

    /** Сильнее и свежее — важнее: они и остаются, когда заметок больше предела. */
    private static List<TraitSignal> strongest(List<TraitSignal> signals) {
        if (signals.size() <= MAX_NOTES) {
            return signals;
        }
        List<TraitSignal> sorted = new ArrayList<>(signals);
        sorted.sort(Comparator.comparingDouble(TraitSignal::intensity).reversed()
                .thenComparing(TraitSignal::at, Comparator.reverseOrder()));
        log.info("Рост личности: заметок {} при пределе {} — спрашиваю о самых сильных",
                signals.size(), MAX_NOTES);
        return List.copyOf(sorted.subList(0, MAX_NOTES));
    }

    /** Отпечаток состава: о том же самом второй раз не спрашивают. */
    private static long mark(List<TraitSignal> signals) {
        return Fingerprint.of(String.join("\n", signals.stream().map(TraitSignal::key).sorted().toList()));
    }

    /** Вопрос модели: что нажито и что показалось заново. */
    private String request(List<TraitSignal> signals) {
        // заметки легли в разное время и разными словами: чужая разметка в них
        // не должна закрывать секции вопроса
        StringBuilder text = new StringBuilder();
        text.append("<current_growth>\n");
        String current = Markup.body(current().strip());
        text.append(current.isBlank() ? "(пусто — ничего нажитого ещё нет)" : current);
        text.append("\n</current_growth>\n\n<signals>\n");
        for (TraitSignal signal : signals) {
            text.append("- ").append(Markup.attribute(signal.trait()))
                    .append("; kind: ").append(Markup.attribute(signal.kind()))
                    .append("; valence: ").append(String.format("%+.1f", signal.valence()))
                    .append("; intensity: ").append(String.format("%.1f", signal.intensity()))
                    .append("; subject: ").append(Markup.attribute(signal.subject()))
                    .append("; at: ").append(signal.at())
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

    /** О чём уже спросили: переживает перезапуск, иначе ночь платится заново. */
    private long readAsked() {
        if (!Files.exists(state)) {
            return 0;
        }
        try {
            return MAPPER.readTree(Files.readString(state)).path("asked").asLong(0);
        } catch (IOException | RuntimeException e) {
            // битая отметка не роняет ночь: проход случится, как в первый раз
            log.warn("Рост личности: отметка прогона не читается ({}): {}", state, e.getMessage());
            return 0;
        }
    }

    private void remember(long mark) {
        try {
            var node = MAPPER.createObjectNode();
            node.put("asked", mark);
            node.put("at", Instant.now(clock).toString());
            AtomicFiles.writeString(state, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (IOException e) {
            // ответ модели уже получен: терять его из-за отметки незачем
            log.warn("Рост личности: отметка прогона не записана ({}): {}", state, e.getMessage());
        }
    }

    private static Optional<Instant> moment(TraitSignal signal) {
        try {
            return Optional.of(Instant.parse(signal.at()));
        } catch (RuntimeException e) {
            // метка не разобралась — заметка всё равно свежая на вид
            return Optional.empty();
        }
    }
}
