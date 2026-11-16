package ru.rainedev.raine.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Наводки владельца: кому и куда он сам её направил в этом разговоре.
 * <p>
 * Владелец кидает ссылку в первом сообщении («глянь кента t.me/friend»), она
 * переспрашивает, и только во втором он отвечает «да, напиши» — уже без ссылки,
 * а первое сообщение к тому моменту помечено прочитанным. Поэтому наводка живёт
 * весь разговор, а не один ход, и очищается вместе с контекстом.
 * <p>
 * Наводкой считается только то, что найдено или открыто в ходу, который начал
 * владелец. Ссылка из группы от незнакомца права писать первой не даёт: иначе
 * любой пост в чате становился бы пропуском в личку.
 */
public final class OwnerLeads {

    private final Set<Long> vouched = ConcurrentHashMap.newKeySet();

    private volatile BooleanSupplier ownerTurn = () -> false;

    /** Чем считать ход начатым владельцем: это знает цикл, а не инструменты. */
    public void ownerTurn(BooleanSupplier flag) {
        this.ownerTurn = flag;
    }

    /** Запомнить сущность, но только если ход начал владелец. */
    public void vouch(long chatId) {
        if (chatId != 0 && ownerTurn.getAsBoolean()) {
            vouched.add(chatId);
        }
    }

    /** Разрешено ли действие: ход владельца или уже данная им наводка. */
    public boolean allows(long chatId) {
        return ownerTurn.getAsBoolean() || vouched.contains(chatId);
    }

    /** Контекст сброшен — разговор кончился, наводки вместе с ним. */
    public void clear() {
        vouched.clear();
    }

    public int size() {
        return vouched.size();
    }
}