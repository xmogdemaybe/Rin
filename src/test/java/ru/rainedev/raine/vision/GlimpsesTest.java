package ru.rainedev.raine.vision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Бюджет свежих просмотров в одном списке стикеров.
 * <p>
 * Пак в тридцать с лишним стикеров открывался минутой работы зрения, а в час,
 * когда квота кончилась, каждый файл ещё и бился об отказ трижды. Список от
 * этого не становится лучше — он просто стоит дороже.
 */
class GlimpsesTest {

    @Test
    void aCachedDescriptionCostsNothing() {
        Glimpses budget = new Glimpses(6);

        for (int attempt = 0; attempt < 50; attempt++) {
            assertTrue(budget.allows(true), "то, что уже разгляжено, лежит в кэше бесплатно");
        }
        assertEquals(0, budget.spent());
    }

    @Test
    void onlyTheAllowedFewAreLookedAtFresh() {
        Glimpses budget = new Glimpses(6);
        int fresh = 0;

        for (int sticker = 0; sticker < 34; sticker++) {
            if (!budget.allows(false)) {
                continue;
            }
            fresh++;
            budget.spent("описание " + sticker);
        }

        assertEquals(6, fresh, "остальные ждут следующего списка");
    }

    @Test
    void oneRefusalClosesTheRestOfTheList() {
        Glimpses budget = new Glimpses(6);
        budget.spent("");

        assertFalse(budget.allows(false), "квота кончилась — в остальные файлы не стучимся");
        assertTrue(budget.allows(true), "кэш по-прежнему бесплатный");
        assertTrue(budget.refused());
    }

    @Test
    void aListWithNoBudgetReliesOnTheCacheOnly() {
        Glimpses budget = new Glimpses(0);

        assertTrue(budget.allows(true));
        assertFalse(budget.allows(false));
    }
}
