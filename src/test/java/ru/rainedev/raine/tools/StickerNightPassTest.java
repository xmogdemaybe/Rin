package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.config.Config;
import ru.rainedev.raine.vision.Glimpses;

/**
 * Ночной досмотр установленных паков.
 * <p>
 * Смысл прохода — добрать то, до чего в разговоре не дошло зрение, и не
 * потратить на это ни закачки, ни взгляда там, где смотреть нечем. Иначе
 * ночной «экономный» проход превращается в самый дорогой за сутки: пак в
 * тридцать стикеров — это тридцать скачанных файлов и три попытки об
 * исчерпанную квоту на каждый.
 */
class StickerNightPassTest {

    private static TdApi.Sticker sticker(long id) {
        TdApi.Sticker sticker = new TdApi.Sticker();
        sticker.id = id;
        sticker.emoji = "🙂";
        return sticker;
    }

    private static List<TdApi.Sticker> pack(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> sticker(i * 100L)).toList();
    }

    /** Что досмотрено, что пропущено и сколько раз зрение вообще позвали. */
    private static final class Probe implements StickerTools.Sight {
        final List<Long> looked = new ArrayList<>();
        int readyCount;
        int blockedCount;
        /** Чем ответы пустые: так выглядит отказ зрения и исчерпанная квота. */
        boolean refuse;

        @Override
        public boolean ready(TdApi.Sticker sticker) {
            if (sticker.id % 200 == 0) {
                readyCount++;
                return true;
            }
            return false;
        }

        @Override
        public boolean blocked(TdApi.Sticker sticker) {
            if (sticker.id % 1000 == 500) {
                blockedCount++;
                return true;
            }
            return false;
        }

        @Override
        public void look(TdApi.Sticker sticker, Glimpses budget) {
            looked.add(sticker.id);
            budget.spent(refuse ? "" : "- CommunicativeMeaning: досмотрен");
        }
    }

    @Test
    void describedAndUnviewableStickersCostNothing() {
        Probe sight = new Probe();
        List<List<TdApi.Sticker>> packs = List.of(pack(10));

        int looked = StickerTools.warmPacks(packs, sight, 20, () -> false);

        assertEquals(5, sight.readyCount, "половина пака была разгляделена раньше");
        assertEquals(1, sight.blockedCount, "один недавно не разгляделся — его пропускают до скачивания");
        assertEquals(4, looked);
        assertEquals(4, sight.looked.size());
        assertTrue(sight.looked.stream().noneMatch(id -> id == 500L),
                "час после неудачи — тот же промах за те же деньги");
    }

    @Test
    void theLimitCountsFreshLooksOnly() {
        Probe sight = new Probe();

        int looked = StickerTools.warmPacks(List.of(pack(40)), sight, 3, () -> false);

        assertEquals(3, looked, "за ночь разглядывается не больше настроенного");
        assertEquals(3, sight.looked.size());
        assertEquals(List.of(100L, 300L, 700L), sight.looked,
                "лимит считают по свежим взглядам, а описанное и недоступное проходят мимо него");
    }

    @Test
    void wakingUpStopsThePassBeforeTheNextSticker() {
        Probe sight = new Probe();
        AtomicInteger checks = new AtomicInteger();
        List<List<TdApi.Sticker>> packs = List.of(List.of(
                sticker(100), sticker(200), sticker(300), sticker(400), sticker(500)));

        int looked = StickerTools.warmPacks(packs, sight, 20, () -> checks.incrementAndGet() > 3);

        assertEquals(4, checks.get(), "проснулась ли — спрашивается перед каждым стикером, и на четвёртом она уже здесь");
        assertEquals(2, looked, "разбуженная ночью не досматривает остаток пака");
        assertEquals(1, sight.readyCount, "после пробуждения до проверки стикеров дело не доходит");
    }

    @Test
    void theFirstQuotaRefusalEndsTheNight() {
        Probe sight = new Probe();
        sight.refuse = true;

        int looked = StickerTools.warmPacks(List.of(pack(40)), sight, 20, () -> false);

        assertEquals(1, sight.looked.size(),
                "отказ зрения закрывает остаток списка: биться об исчерпанную квоту до утра — сотни оплаченных попыток");
        assertEquals(1, looked);
    }

    @Test
    void aLocalSkipDoesNotEndTheRestOfTheList() {
        Probe sight = new Probe();
        // id 500 недоступен локально, id 700 — следующий после него: проход обязан дойти

        int looked = StickerTools.warmPacks(List.of(List.of(sticker(500), sticker(700))), sight, 20, () -> false);

        assertEquals(1, looked);
        assertEquals(List.of(700L), sight.looked,
                "пропуск «смотреть нечем до всякой модели» не отказ зрения и не обрывает очередь");
    }

    @Test
    void anEmptyPackListIsANoOp() {
        Probe sight = new Probe();

        assertEquals(0, StickerTools.warmPacks(List.of(), sight, 20, () -> false));
        assertTrue(sight.looked.isEmpty());
    }

    /** Нулевой лимит при включённом досмотре — это не «досматривать ничего», а сломанная настройка. */
    @Test
    void anImpossibleLimitIsRefused() {
        assertEquals(0, StickerTools.warmPacks(List.of(pack(10)), new Probe(), 0, () -> false),
                "ноль свежих взглядов за ночь — это выключенный досмотр, а не ошибка");
        assertThrows(IllegalArgumentException.class, () -> new Config.StickerWarmup(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new Config.StickerWarmup(true, -3));
        assertEquals(0, new Config.StickerWarmup(false, 0).limit(),
                "снятый тумблер не обязан требовать разумного лимита");
    }
}
