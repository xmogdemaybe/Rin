package ru.rainedev.raine.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import ru.rainedev.raine.telegram.TelegramActions;

/** Опечатка чинится только правкой сообщения: второй реплики со звёздочкой нет. */
class TelegramTypoTest {

    private final List<String> edits = new ArrayList<>();
    private final List<String> sends = new ArrayList<>();

    private TelegramActions actions() {
        return (TelegramActions) Proxy.newProxyInstance(
                TelegramActions.class.getClassLoader(),
                new Class<?>[] {TelegramActions.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("editText")) {
                        edits.add(arguments[0] + ":" + arguments[1] + ":" + arguments[2]
                                + ":" + arguments[3]);
                    } else if (method.getName().equals("sendMessage")) {
                        sends.add(String.valueOf(arguments[1]));
                    }
                    return switch (method.getReturnType().getName()) {
                        case "long" -> 0L;
                        case "int" -> 0;
                        case "boolean" -> false;
                        default -> null;
                    };
                });
    }

    private TelegramTools tools() {
        return new TelegramTools(null, actions(), null, null, null);
    }

    private static java.util.random.RandomGenerator random(boolean noticed) {
        return new java.util.Random() {
            @Override
            public double nextDouble() {
                return noticed ? 0.0 : 1.0;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
    }

    @Test
    void aNoticedTypoIsAlwaysFixedInPlace() {
        List<Long> pauses = new ArrayList<>();

        tools().fixTypo(1L, 2L, "привет", random(true), delay -> {
            pauses.add(delay);
            return true;
        });

        assertEquals(List.of("1:2:привет:false"), edits);
        assertTrue(sends.isEmpty(), "досыла со звёздочкой быть не должно: " + sends);
        assertEquals(List.of(1500L), pauses);
    }

    @Test
    void anUnnoticedTypoIsLeftAlone() {
        tools().fixTypo(1L, 2L, "привет", random(false), delay -> true);

        assertTrue(edits.isEmpty(), edits.toString());
        assertTrue(sends.isEmpty(), sends.toString());
    }

    @Test
    void anInterruptedPauseLeavesTheMessageAlone() {
        tools().fixTypo(1L, 2L, "привет", random(true), delay -> false);

        assertTrue(edits.isEmpty(), edits.toString());
        assertTrue(sends.isEmpty(), sends.toString());
    }
}
