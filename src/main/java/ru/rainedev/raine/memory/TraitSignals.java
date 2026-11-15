package ru.rainedev.raine.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rainedev.raine.storage.AtomicFiles;

/**
 * Копилка заметок о себе: модель пишет днём, ночной отбор разбирает.
 * <p>
 * Заметка ждёт пересказа разговора, а пересказ может не случиться до
 * перезапуска: в одной памяти процесса она исчезала вместе с ним, и повтор
 * через полсуток не набирался никогда. Файл человекочитаемый, запись атомарная,
 * заметки старше {@link TraitSignal#KEEP} не перечитываются.
 */
public final class TraitSignals {

    private static final Logger log = LoggerFactory.getLogger(TraitSignals.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<TraitSignal> pending = new ArrayList<>();
    private final Path file;
    private final Clock clock;

    /** Копилка в памяти: заметки доживут только до конца этого запуска. */
    public TraitSignals() {
        this(null, Clock.systemDefaultZone());
    }

    public TraitSignals(Path file) {
        this(file, Clock.systemDefaultZone());
    }

    /** Часы отдельно — в тестах заметно, как заметка стареет, не дожидаясь календаря. */
    TraitSignals(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        load();
    }

    public synchronized void add(TraitSignal signal) {
        pending.add(signal);
        keepFresh();
        store();
    }

    /** Забирает всё накопленное: копилка опустошается, заметки ждут записи дневника. */
    public synchronized List<TraitSignal> drain() {
        List<TraitSignal> taken = List.copyOf(pending);
        pending.clear();
        store();
        return taken;
    }

    /** Что лежит в копилке сейчас: ночной отбор читает, не опустошая. */
    public synchronized List<TraitSignal> snapshot() {
        keepFresh();
        return List.copyOf(pending);
    }

    /** Сколько таких же заметок уже лежит в копилке — по совпадению формулировки. */
    public synchronized long countSame(String trait) {
        String marker = trait.strip().toLowerCase();
        return pending.stream()
                .filter(signal -> signal.trait().strip().toLowerCase().equals(marker))
                .count();
    }

    /** Запись дневника не случилась — заметки живут дальше, до следующего раза. */
    public synchronized void requeue(List<TraitSignal> signals) {
        pending.addAll(signals);
        keepFresh();
        store();
    }

    private void load() {
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            pending.addAll(MAPPER.readValue(Files.readString(file), new TypeReference<List<TraitSignal>>() { }));
            keepFresh();
            log.info("Заметок о себе с прошлого запуска: {}", pending.size());
        } catch (IOException | RuntimeException e) {
            // битый файл не роняет запуск: копилка начнётся с чистого листа
            log.warn("Копилка заметок не читается ({}): {}", file, e.getMessage());
            pending.clear();
        }
    }

    private void store() {
        if (file == null) {
            return;
        }
        try {
            AtomicFiles.writeString(file, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(pending));
        } catch (IOException e) {
            // в памяти заметки целы: несостоявшийся диск не должен стоить дня
            log.warn("Копилка заметок не записана ({}): {}", file, e.getMessage());
        }
    }

    /** Заметки старше срока повтора уже не дождутся — копилка не растёт вечно. */
    private void keepFresh() {
        Instant horizon = Instant.now(clock).minus(TraitSignal.KEEP);
        pending.removeIf(signal -> moment(signal).map(at -> at.isBefore(horizon)).orElse(false));
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
