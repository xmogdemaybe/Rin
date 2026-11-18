package ru.rainedev.raine.memory;

import java.util.List;
import java.util.Set;
import ru.rainedev.raine.llm.Message;

/** Источник воспоминаний для контекста. Отдельный интерфейс — чтобы цикл не знал про эмбеддинги. */
@FunctionalInterface
public interface Memory {

    /**
     * @param recentContext последние сообщения — по ним понятно, о чём сейчас речь
     * @param audience      кому показывать: часть записей этому зрителю закрыта
     * @return текст воспоминаний для подмешивания или пустая строка
     */
    String recall(List<Message> recentContext, Audience audience);

    /**
     * Что уже подмешано в этот разговор — снимок для отката. Память без
     * отпечатков (заглушки, {@link #NONE}) отвечает пустотой.
     */
    default Set<Long> markInjected() {
        return Set.of();
    }

    /** Вернуть список вспомненного к прежнему состоянию: ход не состоялся. */
    default void restoreInjected(Set<Long> snapshot) {
        // нечего возвращать
    }

    /** Память отключена — Raine помнит только текущий разговор. */
    Memory NONE = (context, audience) -> "";
}
