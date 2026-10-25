package ru.rainedev.raine.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * О неудачной съёмке она узнаёт из уведомления, текст которого — чужая ошибка.
 * Бывает, что у ошибки текста нет: тогда в уведомлении стояло бы «null», и по
 * нему не решить, пробовать снова или оставить затею.
 */
class PhotoFailureTextTest {

    @Test
    void anErrorWithoutTextStillSaysSomethingSheCanActOn() {
        assertEquals("Съёмка оборвалась без внятной причины.",
                ImageGenerator.failureFor(new NullPointerException()));
        assertEquals("Съёмка оборвалась без внятной причины.",
                ImageGenerator.failureFor(new IllegalStateException("  ")));
    }

    @Test
    void aNamedFailureReachesHerWhole() {
        // отзыв о снимке — единственное, из чего видно, что именно не так;
        // сокращать его нельзя, иначе правки будут вслепую
        String verdict = "Не вышло сделать удачный снимок: у неё три руки. Попробуй описать снимок короче.";

        assertEquals(verdict, ImageGenerator.failureFor(new IllegalStateException(verdict)));
    }
}
