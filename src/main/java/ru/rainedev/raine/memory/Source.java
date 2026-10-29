package ru.rainedev.raine.memory;

import ru.rainedev.raine.phone.ChatKind;

/**
 * Откуда запись попала в дневник.
 * <p>
 * Порядок объявления и есть строгость: личка владельца строже чужой лички, та
 * строже группы, а общее не секретно никому. Разговор, задевший несколько чатов,
 * даёт записи строжайшую метку из них — иначе сказанное владельцем уехало бы в
 * общий круг.
 */
public enum Source {
    OWNER_DM("owner_dm"),
    PRIVATE_DM("private_dm"),
    GROUP("group"),
    GENERAL("general");

    private final String wire;

    Source(String wire) {
        this.wire = wire;
    }

    /** Как метка лежит в файле записи. */
    public String wire() {
        return wire;
    }

    /** У прежних записей метки нет вовсе — они остаются общими. */
    public static Source of(String wire) {
        for (Source source : values()) {
            if (source.wire.equals(wire)) {
                return source;
            }
        }
        return GENERAL;
    }

    /**
     * @param owner это личка владельца. То, что он сам написал в общий чат,
     *              секретом лички не считается
     */
    public static Source of(ChatKind kind, boolean owner) {
        return switch (kind) {
            case DM -> owner ? OWNER_DM : PRIVATE_DM;
            case GROUP, CHANNEL -> GROUP;
        };
    }

    /** Строжайшая из двух: секретность задаёт самый закрытый из затронутых чатов. */
    public Source strictest(Source other) {
        return ordinal() <= other.ordinal() ? this : other;
    }
}
