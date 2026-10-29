package ru.rainedev.raine.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.phone.ChatKind;

class SourceTest {

    @Test
    void privateChatWithTheOwnerIsTheStrictest() {
        assertEquals(Source.OWNER_DM, Source.of(ChatKind.DM, true));
    }

    @Test
    void someoneElsesPrivateChatIsStricterThanAGroup() {
        assertEquals(Source.PRIVATE_DM, Source.of(ChatKind.DM, false));
        assertEquals(Source.PRIVATE_DM, Source.PRIVATE_DM.strictest(Source.GROUP));
    }

    @Test
    void whatTheOwnerWroteInAGroupIsNotHisPrivateChat() {
        assertEquals(Source.GROUP, Source.of(ChatKind.GROUP, true));
    }

    @Test
    void aChannelIsNotMoreSecretThanAGroup() {
        assertEquals(Source.GROUP, Source.of(ChatKind.CHANNEL, false));
    }

    @Test
    void theStrictestOfTheChatsInTheConversationWins() {
        assertEquals(Source.OWNER_DM, Source.GENERAL.strictest(Source.OWNER_DM));
        assertEquals(Source.PRIVATE_DM,
                List.of(Source.GENERAL, Source.GROUP, Source.PRIVATE_DM).stream()
                        .reduce(Source.GENERAL, Source::strictest));
    }

    @Test
    void anEntryWithoutALabelReadsAsGeneral() {
        // прежние записи никто не переписывает: у них метки нет вовсе
        assertEquals(Source.GENERAL, Source.of(""));
        assertEquals(Source.GENERAL, Source.of(null));
        assertEquals(Source.GENERAL, Source.of("незнакомое слово"));
    }

    @Test
    void labelsAreWrittenTheWayTheyWereAgreed() {
        assertEquals("owner_dm", Source.OWNER_DM.wire());
        assertEquals("private_dm", Source.PRIVATE_DM.wire());
        assertEquals("group", Source.GROUP.wire());
        assertEquals("general", Source.GENERAL.wire());
        assertEquals(Source.OWNER_DM, Source.of("owner_dm"));
    }
}
