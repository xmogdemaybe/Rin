package ru.rainedev.raine.memory;

import java.util.List;

/**
 * Запись дневника — единица долгой памяти.
 *
 * @param id         он же имя файла: unix-время создания
 * @param body       текст записи, как его читает модель
 * @param embedding  вектор для поиска по смыслу
 * @param metadata   опыт использования записи и то, что известно о её происхождении
 */
public record DiaryEntry(String id, String body, double[] embedding, Metadata metadata) {

    /**
     * @param score      накопленная полезность: растёт, когда запись оказывается к месту
     * @param confidence 1 означает факт, а не домысел — такие записи не переписываются
     * @param lastUsed   когда последний раз попадала в контекст
     * @param usageCount сколько раз пригодилась
     * @param signals    заметки о себе, записанные в момент создания записи;
     *                   у прежних записей их нет, формат файла остаётся совместимым
     * @param source     откуда запись попала в дневник; прежние записи метки не имеют
     *                   и читаются как общие
     * @param sourceChat чат, который принёс запись; 0 — его нет или их несколько,
     *                   и тогда случайному человеку запись не показывается вовсе
     */
    public record Metadata(double score, double confidence, String lastUsed, int usageCount,
                           List<TraitSignal> signals, Source source, long sourceChat) {

        public Metadata(double score, double confidence, String lastUsed, int usageCount) {
            this(score, confidence, lastUsed, usageCount, List.of(), Source.GENERAL, 0);
        }

        public static Metadata fresh() {
            return new Metadata(0, 0, "never", 0);
        }

        /** Запись пригодилась: чем выше близость, тем заметнее прибавка. */
        public Metadata used(double relatedness, String now) {
            return new Metadata(score + (relatedness - 0.5) * 2, confidence, now, usageCount + 1,
                    signals, source, sourceChat);
        }
    }

    public DiaryEntry withMetadata(Metadata updated) {
        return new DiaryEntry(id, body, embedding, updated);
    }

    /** Откуда запись — метка вместе с чатом: ровно так, по чему решает Audience. */
    public Origin origin() {
        return new Origin(metadata.sourceChat(), metadata.source());
    }
}
