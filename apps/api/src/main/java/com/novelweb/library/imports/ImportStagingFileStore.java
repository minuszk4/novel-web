package com.novelweb.library.imports;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("postgres")
public class ImportStagingFileStore {

    private final Path root;
    private final long maxUploadBytes;

    public ImportStagingFileStore(ImportStagingProperties properties) {
        this.root = properties.root().toAbsolutePath().normalize();
        this.maxUploadBytes = properties.maxUploadBytes();
    }

    public void verifyAvailable() throws IOException {
        Files.createDirectories(root);
        if (!Files.isDirectory(root) || !Files.isWritable(root)) {
            throw new IOException("Import staging directory is not writable");
        }
    }

    public StoredFile store(UUID importId, InputStream input, long expectedSize) throws IOException {
        verifyAvailable();
        Path target = targetPath(importId);
        if (Files.exists(target)) throw new IOException("An object already exists for this import");
        Path temporary = Files.createTempFile(root, ".import-" + importId + "-", ".part");
        boolean moved = false;
        try {
            MessageDigest digest = sha256Digest();
            long size = 0;
            byte[] buffer = new byte[8192];
            try (OutputStream output = Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read;
                    if (size > maxUploadBytes) throw new UploadTooLargeException(maxUploadBytes);
                    if (size > expectedSize) throw new UploadSizeMismatchException(expectedSize, size);
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            if (size != expectedSize) throw new UploadSizeMismatchException(expectedSize, size);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
            moved = true;
            return new StoredFile(size, digest.digest(), target);
        } finally {
            Files.deleteIfExists(temporary);
            if (!moved) Files.deleteIfExists(target);
        }
    }

    public void delete(UUID importId) throws IOException {
        if (!Files.isDirectory(root)) return;
        Files.deleteIfExists(targetPath(importId));
        try (var files = Files.list(root)) {
            String prefix = ".import-" + importId + "-";
            for (Path file : files.filter(path -> path.getFileName().toString().startsWith(prefix)).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    public Path targetPath(UUID importId) {
        Path target = root.resolve(importId + ".upload").normalize();
        if (!target.getParent().equals(root)) throw new IllegalArgumentException("Invalid import storage key");
        return target;
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record StoredFile(long sizeBytes, byte[] sha256, Path path) {
        public String sha256Hex() {
            return HexFormat.of().formatHex(sha256);
        }
    }

    public static final class UploadTooLargeException extends IOException {
        private final long maxBytes;

        public UploadTooLargeException(long maxBytes) {
            super("Uploaded content exceeds the configured maximum");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }

    public static final class UploadSizeMismatchException extends IOException {
        public UploadSizeMismatchException(long expected, long actual) {
            super("Uploaded content length does not match the declared size");
        }
    }
}
