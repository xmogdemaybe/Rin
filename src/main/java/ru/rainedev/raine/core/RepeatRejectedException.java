package ru.rainedev.raine.core;

/**
 * Отказ антиповтора: модель предложила фразу, слишком похожую на уже отправленную.
 * В цикле даётся ровно одна попытка переформулировать за ход.
 */
public final class RepeatRejectedException extends LowQualityException {

    public RepeatRejectedException(String reason) {
        super(reason);
    }
}
