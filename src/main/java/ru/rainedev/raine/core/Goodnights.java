package ru.rainedev.raine.core;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import ru.rainedev.raine.memory.TrustLedger;

/**
 * Ночное прощание и утреннее приветствие. Человек так и делает: перед сном
 * вспоминает своего человека, с утра здоровается с ним первым. Кубик решает,
 * выпадет ли порыв именно сегодня, — переписка идёт по обычной механике,
 * а порыв стоит пару обращений к модели.
 * <p>
 * Кому не ответили, тому больше не навязываются: с человеком, которому она
 * уже писала, слово держится паузу в две недели. Книга пауз своя у ночи и своя
 * у утра — сказав «спокойной ночи», она не лишает человека «доброго утра».
 * Память живёт в процессе: после перезапуска кубик крутится заново.
 */
public final class Goodnights {

    /** Насколько вероятно слово для человека — по десятой доле уровня доверия. */
    private static final double CHANCE_PER_LEVEL = 0.1;

    /** Потолок шанса для одного человека: пять ночей из десяти. */
    private static final double MAX_CHANCE = 0.5;

    /** Близкие начинаются с трёх плюсов: с меньшим и писать незачем. */
    private static final int MIN_LEVEL = 3;

    /** Владелец особый: доброе слово ему почти каждую ночь. */
    private static final double OWNER_CHANCE = 0.8;

    /** С человеком, которому это слово только что сказала, пауза в две недели. */
    private static final Duration COOLDOWN = Duration.ofDays(14);

    /** Кому поставить порыв: id чата и имя, как она человека знает. */
    public record Receiver(long id, String name) {}

    private final TrustLedger ledger;
    private final long ownerId;
    private final String ownerName;
    private final RandomGenerator random;
    private final Clock clock;
    private final Map<Long, Instant> lastGoodnight = new HashMap<>();
    private final Map<Long, Instant> lastMorning = new HashMap<>();

    public Goodnights(TrustLedger ledger, long ownerId, String ownerName, RandomGenerator random) {
        this(ledger, ownerId, ownerName, random, Clock.systemDefaultZone());
    }

    /** Часы отдельно — в тестах видно, как держится пауза между словами. */
    Goodnights(TrustLedger ledger, long ownerId, String ownerName, RandomGenerator random, Clock clock) {
        this.ledger = ledger;
        this.ownerId = ownerId;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.random = random;
        this.clock = clock;
    }

    /** Кому выпало «спокойной ночи» прямо сейчас. */
    public List<Receiver> drawForNight() {
        return draw(lastGoodnight);
    }

    /** Кому выпало «доброе утро» прямо сейчас — по своей книге пауз. */
    public List<Receiver> drawForMorning() {
        return draw(lastMorning);
    }

    /**
     * Кому выпало сказать слово: владелец идёт первым, близкие из файла —
     * за ним, каждый по уровню. Владельцу пауза не положена: он и есть
     * человек, к которому она возвращается каждый вечер, — ради него и
     * высокий шанс в кубике.
     */
    private List<Receiver> draw(Map<Long, Instant> book) {
        List<Receiver> picked = new ArrayList<>();
        Instant now = Instant.now(clock);
        if (ownerId > 0 && random.nextDouble() < OWNER_CHANCE) {
            picked.add(new Receiver(ownerId, ownerName));
            book.put(ownerId, now);
        }
        for (TrustLedger.Entry entry : ledger.entries()) {
            if (entry.level() < MIN_LEVEL || !pauseIsOver(book, entry.id(), now)) {
                continue;
            }
            double chance = Math.min(entry.level() * CHANCE_PER_LEVEL, MAX_CHANCE);
            if (random.nextDouble() < chance) {
                picked.add(new Receiver(entry.id(), entry.name()));
                book.put(entry.id(), now);
            }
        }
        return picked;
    }

    /** Пауза между одинаковыми словами: сказала — и две недели молчит. */
    private static boolean pauseIsOver(Map<Long, Instant> book, long id, Instant now) {
        Instant last = book.get(id);
        return last == null || Duration.between(last, now).compareTo(COOLDOWN) >= 0;
    }
}
