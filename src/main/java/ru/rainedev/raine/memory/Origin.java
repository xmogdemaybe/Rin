package ru.rainedev.raine.memory;

/**
 * Откуда взялась запись: чат и его строгость.
 * <p>
 * Одной парой, а не двумя отдельными величинами: метка разговора и чат, который
 * её задал, меняются вместе, иначе запись получила бы метку одного чата, а
 * идентификатор — другого и показалась бы случайному человеку его собственным
 * секретом.
 *
 * @param chatId чат, принёсший запись; 0 — чата нет или их несколько
 * @param source строжайшая метка из затронутых чатов
 */
public record Origin(long chatId, Source source) {

    /** Ни метки, ни чата: общее — и показывать можно всем. */
    public static final Origin GENERAL = new Origin(0, Source.GENERAL);

    /** Строжайшая из двух; при равной строгости остаётся та, что уже стояла. */
    public Origin strictest(Origin other) {
        return source.strictest(other.source) == source ? this : other;
    }
}
