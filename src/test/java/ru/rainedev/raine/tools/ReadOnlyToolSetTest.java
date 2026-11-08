package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ru.rainedev.raine.core.Tool;
import ru.rainedev.raine.core.Toolbox;
import ru.rainedev.raine.telegram.TelegramActions;

/**
 * Безопасный режим убирает из набора всё, что заметит собеседник. Съёмка —
 * из того же ряда: ещё до готового снимка чат показывает «загружает фото…»,
 * а отправить сам снимок в этом режиме нечем.
 */
class ReadOnlyToolSetTest {

    /** Инструменты собираются без Telegram: здесь важны права, а не ответы сервера. */
    @Test
    void takingAPhotoCountsAsAnAction() {
        TelegramTools tools = new TelegramTools(null, new TelegramActions.ReadOnly(), null, null, null);
        var photo = tools.takePhoto(1);

        assertTrue(photo.mutates(), "съёмка показывает чату индикатор — значит, это действие");

        Toolbox safe = new Toolbox().actionsAllowed(false).add(photo);
        assertFalse(safe.names().contains("take_photo"),
                "в безопасном режиме съёмки в наборе быть не должно: " + safe.names());
    }

    /**
     * Пак по чужой ссылке — то же самое, что съёмка: Telegram до готового
     * результата ничего не показывает собеседнику, но у аккаунта появляется
     * установленный пак. Ссылку мог прислать кто угодно, поэтому в безопасном
     * режиме устанавливать по ней нечем.
     */
    @Test
    void installingAStickerPackCountsAsAnAction() {
        Tool pack = new StickerTools(null, null).pack();

        assertTrue(pack.mutates(), "открытый пак ставится себе — значит, это действие");

        Toolbox safe = new Toolbox().actionsAllowed(false).add(pack);
        assertFalse(safe.names().contains("sticker_pack"),
                "в безопасном режиме чужая ссылка не должна ничего устанавливать: " + safe.names());
    }
}
