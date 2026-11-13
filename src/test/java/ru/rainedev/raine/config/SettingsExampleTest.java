package ru.rainedev.raine.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Файл-образец — то, с чего начинают новую установку, и заодно единственная
 * полная документация на настройки: рабочий конфиг бот дописывает сам, а
 * человек смотрит сюда. Поэтому набор ключей в образце обязан совпадать с
 * набором, который спрашивает код. Расхождение выглядит безобидно ровно до
 * первой установки, где прокси и лимитов просто не существует в природе.
 */
class SettingsExampleTest {

    private static final Path CODE = Path.of("src/main/java/ru/rainedev/raine/config/Config.java");
    private static final Path EXAMPLE = Path.of("config.properties.example");

    /** Настройку объявляет обращение к Settings; других мест в проекте нет. */
    private static final Pattern DECLARED = Pattern.compile(
            "s\\.(?:require|get|integer|number|flag|fraction|path|time|inherit)\\(\\s*\"([a-z0-9_]+)\"");

    private static final Pattern WRITTEN = Pattern.compile("(?m)^([a-z0-9_]+)=");

    private static Set<String> keys(Path file, Pattern pattern) throws IOException {
        assertTrue(Files.isRegularFile(file), "нечего сверять: " + file.toAbsolutePath() + " не читается");
        String body = Files.readString(file);
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        // Пустой или случайно короткий список — поломка самой проверки, а не
        // зелёный результат: без него любое расхождение осталось бы незамеченным
        assertTrue(found.size() > 50,
                "проверка не нашла настроек в " + file + " (найдено " + found.size() + ") — шаблон сломался");
        return found;
    }

    @Test
    void everySettingTheCodeAsksForIsDocumented() throws IOException {
        Set<String> declared = keys(CODE, DECLARED);
        Set<String> documented = keys(EXAMPLE, WRITTEN);
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(documented);
        assertTrue(missing.isEmpty(),
                "в образце не описаны настройки, которые код читает: " + String.join(", ", missing));
    }

    @Test
    void theExamplePromisesNothingTheCodeIgnores() throws IOException {
        Set<String> declared = keys(CODE, DECLARED);
        Set<String> documented = keys(EXAMPLE, WRITTEN);
        Set<String> stale = new TreeSet<>(documented);
        stale.removeAll(declared);
        assertTrue(stale.isEmpty(),
                "в образце лежат настройки, которые код больше не спрашивает: " + String.join(", ", stale));
    }
}
