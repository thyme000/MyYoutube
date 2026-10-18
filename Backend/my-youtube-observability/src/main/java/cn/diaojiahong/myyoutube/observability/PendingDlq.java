package cn.diaojiahong.myyoutube.observability;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

final class PendingDlq {
    private final Path directory;
    private final String group;
    PendingDlq(Path directory, String group) throws IOException {
        this.directory = directory.toAbsolutePath();
        this.group = group;
        Files.createDirectories(this.directory);
    }
    Path key(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(group.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return directory.resolve(HexFormat.of().formatHex(digest.digest(body)) + ".pending");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    boolean contains(byte[] body) throws IOException {
        try { Files.readAttributes(key(body), java.nio.file.attribute.BasicFileAttributes.class); return true; }
        catch (NoSuchFileException absent) { return false; }
    }
    void save(byte[] body, IngestFailure.Reason reason) throws IOException {
        Path temporary = Files.createTempFile(directory, ".pending-", ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer value = StandardCharsets.UTF_8.encode(reason.name() + "\n");
                while (value.hasRemaining()) file.write(value);
                file.force(true);
            }
            Files.move(temporary, key(body), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            syncDirectory();
        } finally { Files.deleteIfExists(temporary); }
    }
    void remove(byte[] body) throws IOException { Files.deleteIfExists(key(body)); syncDirectory(); }
    private void syncDirectory() throws IOException {
        try (FileChannel directoryChannel = FileChannel.open(directory, StandardOpenOption.READ)) { directoryChannel.force(true); }
    }
}
