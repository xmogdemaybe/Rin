package ru.rainedev.raine.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * Наводки владельца живут весь разговор, а не один ход: ссылку он даёт в первом
 * сообщении, а «да, напиши» отвечает во втором, когда ссылка уже прочитана.
 */
class OwnerLeadsTest {

    private final AtomicBoolean ownerTurn = new AtomicBoolean();
    private final OwnerLeads leads = new OwnerLeads();

    OwnerLeadsTest() {
        leads.ownerTurn(ownerTurn::get);
    }

    @Test
    void aLeadGivenInAnOwnerTurnSurvivesTheNextTurn() {
        ownerTurn.set(true);
        leads.vouch(4242L);

        ownerTurn.set(false);
        assertTrue(leads.allows(4242L), "владелец подтвердил во втором сообщении — наводка ещё жива");
    }

    @Test
    void anOwnerTurnAllowsEvenWithoutARecordedLead() {
        ownerTurn.set(true);

        assertTrue(leads.allows(777L), "ход начал владелец — этого достаточно");
    }

    @Test
    void aStrangerTurnDoesNotCreateALead() {
        ownerTurn.set(false);
        leads.vouch(4242L);

        assertFalse(leads.allows(4242L), "ссылка из группы от незнакомца права не даёт");
        assertEquals(0, leads.size());
    }

    @Test
    void clearingTheConversationClearsTheLeads() {
        ownerTurn.set(true);
        leads.vouch(4242L);
        ownerTurn.set(false);

        leads.clear();

        assertFalse(leads.allows(4242L), "новый разговор — старые ссылки не пропуск");
    }

    @Test
    void zeroIsNotALead() {
        ownerTurn.set(true);
        leads.vouch(0L);
        assertEquals(0, leads.size());

        ownerTurn.set(false);
        assertFalse(leads.allows(0L), "нулевой идентификатор наводкой не становится");
    }
}