package ru.rainedev.raine.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Complete the new file before replacing the previous version. */
public final class AtomicFiles {
    private AtomicFiles() {}
    public static void writeString(Path file, String content) throws IOException {
        writeBytes(file, StandardCharsets.UTF_8.encode(content));
    }

    /** То же для байтов: картинку не нужно перекодировать в текст. */
    public static void writeBytes(Path file, byte[] content) throws IOException {
        writeBytes(file, ByteBuffer.wrap(content));
    }

    private static void writeBytes(Path file, ByteBuffer content) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".raine-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            // If atomic replacement is unsupported, preserve the old file and report failure.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
