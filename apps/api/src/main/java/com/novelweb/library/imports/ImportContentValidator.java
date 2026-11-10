package com.novelweb.library.imports;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.CharConversionException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Checks uploaded bytes and EPUB archive bounds before an upload can leave private staging state. */
@Component
@Profile("postgres")
public class ImportContentValidator {

    private static final byte[] EPUB_MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
    private static final int HTML_PREFIX_LIMIT = 4096;
    private static final int ZIP_LOCAL_HEADER_BYTES = 30;
    private static final int ZIP_END_RECORD_BYTES = 22;
    private static final int ZIP_MAX_COMMENT_BYTES = 65535;
    private static final Pattern HTML_START = Pattern.compile(
            "(?is)^<(?:!doctype\\s+html\\b|html\\b|head\\b|body\\b|title\\b|meta\\b|link\\b|"
                    + "main\\b|article\\b|section\\b|div\\b|p\\b|h[1-6]\\b|ul\\b|ol\\b|table\\b|"
                    + "blockquote\\b|pre\\b|script\\b|style\\b|a\\b|img\\b|span\\b|svg\\b|br\\b|hr\\b)");
    private static final Pattern DRIVE_PREFIX = Pattern.compile("^[A-Za-z]:.*");

    private final ImportStagingProperties properties;

    public ImportContentValidator(ImportStagingProperties properties) {
        this.properties = properties;
    }

    public void validate(Path path, String filename, String declaredContentType)
            throws IOException, InvalidContentException {
        String extension = extension(filename);
        if (extension.equals("epub")) {
            if (!matchesDeclaredType(declaredContentType, Set.of(
                    "application/epub+zip", "application/zip", "application/octet-stream"))) {
                throw new InvalidContentException("Declared MIME type does not match EPUB content");
            }
            validateEpub(path);
        } else if (Set.of("txt", "md", "markdown", "html", "htm").contains(extension)) {
            boolean html = extension.equals("html") || extension.equals("htm");
            Set<String> expectedTypes = html
                    ? Set.of("text/html", "application/xhtml+xml", "application/octet-stream")
                    : extension.equals("txt")
                            ? Set.of("text/plain", "application/octet-stream")
                            : Set.of("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream");
            if (!matchesDeclaredType(declaredContentType, expectedTypes)) {
                throw new InvalidContentException("Declared MIME type does not match the file extension");
            }
            validateText(path, html);
        } else {
            throw new InvalidContentException("Unsupported import extension");
        }
    }

    private static boolean matchesDeclaredType(String contentType, Set<String> expectedTypes) {
        if (contentType == null || contentType.isBlank()) return true;
        String normalized = contentType.trim().toLowerCase(Locale.ROOT);
        int parameters = normalized.indexOf(';');
        if (parameters >= 0) normalized = normalized.substring(0, parameters).trim();
        return expectedTypes.contains(normalized);
    }

    private void validateText(Path path, boolean html) throws IOException, InvalidContentException {
        long deadline = deadline();
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder prefix = new StringBuilder(HTML_PREFIX_LIMIT);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), decoder))) {
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) {
                checkDeadline(deadline);
                for (int index = 0; index < count; index++) {
                    char character = buffer[index];
                    if (Character.isISOControl(character)
                            && character != '\t' && character != '\n' && character != '\r' && character != '\f') {
                        throw new InvalidContentException("Text content contains binary control bytes");
                    }
                }
                if (prefix.length() < HTML_PREFIX_LIMIT) {
                    prefix.append(buffer, 0, Math.min(count, HTML_PREFIX_LIMIT - prefix.length()));
                }
            }
        } catch (CharacterCodingException | CharConversionException exception) {
            throw new InvalidContentException("Text content is not valid UTF-8", exception);
        }
        if (html && !hasHtmlStart(prefix)) {
            throw new InvalidContentException("HTML extension does not match the text content");
        }
        if (!html && hasHtmlStart(prefix)) {
            throw new InvalidContentException("Text extension does not match HTML content");
        }
    }

    private void validateEpub(Path path) throws IOException, InvalidContentException {
        long deadline = deadline();
        validateFirstLocalHeader(path);
        Set<String> normalizedPaths = new HashSet<>();
        Set<String> filePaths = new HashSet<>();
        boolean foundContainer = false;
        boolean foundMimetype = false;
        long totalExpanded = 0;
        int entryCount = 0;

        try (InputStream input = Files.newInputStream(path);
             ZipInputStream archive = new ZipInputStream(input, StandardCharsets.UTF_8)) {
            ZipEntry entry = archive.getNextEntry();
            if (entry == null) throw new InvalidContentException("EPUB archive is empty");
            if (!"mimetype".equals(entry.getName()) || entry.isDirectory()) {
                throw new InvalidContentException("EPUB mimetype must be the first archive entry");
            }

            do {
                checkDeadline(deadline);
                if (++entryCount > properties.maxArchiveEntries()) {
                    throw new InvalidContentException("EPUB archive contains too many entries");
                }
                validateEntryPath(entry, normalizedPaths, filePaths);
                int method = entry.getMethod();
                if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED) {
                    throw new InvalidContentException("EPUB archive uses an unsupported compression method");
                }

                long expectedSize = entry.getSize();
                if (expectedSize > properties.maxArchiveEntryBytes()
                        || expectedSize > properties.maxArchiveExpandedBytes() - totalExpanded) {
                    throw new InvalidContentException("EPUB archive exceeds its expanded-byte limit");
                }
                long compressedSize = entry.getCompressedSize();
                if (expectedSize >= 0 && compressedSize >= 0 && method == ZipEntry.STORED
                        && expectedSize != compressedSize) {
                    throw new InvalidContentException("Stored EPUB entry has inconsistent sizes");
                }
                byte[] extra = entry.getExtra();
                if ("mimetype".equals(entry.getName()) && extra != null && extra.length > 0) {
                    throw new InvalidContentException("EPUB mimetype entry must not contain extra fields");
                }

                ByteArrayOutputStream mimetype = "mimetype".equals(entry.getName())
                        ? new ByteArrayOutputStream(EPUB_MIMETYPE.length) : null;
                long entryExpanded = 0;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    checkDeadline(deadline);
                    if (read > properties.maxArchiveEntryBytes() - entryExpanded
                            || read > properties.maxArchiveExpandedBytes() - totalExpanded) {
                        throw new InvalidContentException("EPUB archive exceeds its expanded-byte limit");
                    }
                    entryExpanded += read;
                    totalExpanded += read;
                    if (mimetype != null) {
                        if (entryExpanded > EPUB_MIMETYPE.length) {
                            throw new InvalidContentException("EPUB mimetype content is invalid");
                        }
                        mimetype.write(buffer, 0, read);
                    }
                }
                archive.closeEntry();
                expectedSize = entry.getSize();
                compressedSize = entry.getCompressedSize();
                if (expectedSize < 0 || compressedSize < 0 || entryExpanded != expectedSize) {
                    throw new InvalidContentException("EPUB entry size does not match its directory record");
                }
                if (expectedSize > 0 && (compressedSize == 0
                        || (double) expectedSize / compressedSize > properties.maxArchiveCompressionRatio())) {
                    throw new InvalidContentException("EPUB archive exceeds its compression-ratio limit");
                }
                if ("mimetype".equals(entry.getName())) {
                    if (method != ZipEntry.STORED || entryExpanded != EPUB_MIMETYPE.length
                            || !java.util.Arrays.equals(mimetype.toByteArray(), EPUB_MIMETYPE)) {
                        throw new InvalidContentException("EPUB mimetype content or compression is invalid");
                    }
                    foundMimetype = true;
                }
                if ("META-INF/container.xml".equals(entry.getName())) {
                    if (entry.isDirectory() || entryExpanded == 0) {
                        throw new InvalidContentException("EPUB container.xml is empty");
                    }
                    foundContainer = true;
                }
                entry = archive.getNextEntry();
            } while (entry != null);
        } catch (ZipException | IllegalArgumentException exception) {
            throw new InvalidContentException("EPUB archive structure is invalid", exception);
        }

        if (!foundMimetype || !foundContainer) {
            throw new InvalidContentException("EPUB archive is missing required container files");
        }
        validateCentralDirectorySummary(path, entryCount);
    }

    private void validateFirstLocalHeader(Path path) throws IOException, InvalidContentException {
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (file.length() < ZIP_LOCAL_HEADER_BYTES) {
                throw new InvalidContentException("EPUB file is too short to contain a ZIP local header");
            }
            byte[] header = new byte[ZIP_LOCAL_HEADER_BYTES];
            file.readFully(header);
            if (readLittleEndianInt(header, 0) != 0x04034b50) {
                throw new InvalidContentException("EPUB file does not start with a ZIP local header");
            }
            int flags = readLittleEndianShort(header, 6);
            int method = readLittleEndianShort(header, 8);
            long compressedSize = Integer.toUnsignedLong(readLittleEndianInt(header, 18));
            long uncompressedSize = Integer.toUnsignedLong(readLittleEndianInt(header, 22));
            int filenameLength = readLittleEndianShort(header, 26);
            int extraLength = readLittleEndianShort(header, 28);
            boolean hasDataDescriptor = (flags & 0x0008) != 0;
            boolean badLocalSizes = hasDataDescriptor
                    ? !((compressedSize == 0 || compressedSize == EPUB_MIMETYPE.length)
                            && (uncompressedSize == 0 || uncompressedSize == EPUB_MIMETYPE.length))
                    : compressedSize != EPUB_MIMETYPE.length || uncompressedSize != EPUB_MIMETYPE.length;
            int encryptionFlags = (1 << 0) | (1 << 6) | (1 << 13);
            if ((flags & encryptionFlags) != 0 || method != ZipEntry.STORED || badLocalSizes
                    || filenameLength != "mimetype".length() || extraLength != 0) {
                throw new InvalidContentException("EPUB first local entry header is invalid");
            }
            byte[] name = new byte[filenameLength];
            file.readFully(name);
            if (!java.util.Arrays.equals(name, "mimetype".getBytes(StandardCharsets.UTF_8))) {
                throw new InvalidContentException("EPUB mimetype must be the first local entry");
            }
            byte[] value = new byte[EPUB_MIMETYPE.length];
            file.readFully(value);
            if (!java.util.Arrays.equals(value, EPUB_MIMETYPE)) {
                throw new InvalidContentException("EPUB mimetype content is invalid");
            }
        }
    }

    private void validateCentralDirectorySummary(Path path, int localEntryCount)
            throws IOException, InvalidContentException {
        long fileSize = Files.size(path);
        int tailLength = (int) Math.min(fileSize, ZIP_END_RECORD_BYTES + ZIP_MAX_COMMENT_BYTES);
        byte[] tail = new byte[tailLength];
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(fileSize - tailLength);
            file.readFully(tail);
        }
        int endRecord = -1;
        for (int offset = tail.length - ZIP_END_RECORD_BYTES; offset >= 0; offset--) {
            if (readLittleEndianInt(tail, offset) != 0x06054b50) continue;
            int commentLength = readLittleEndianShort(tail, offset + 20);
            if (offset + ZIP_END_RECORD_BYTES + commentLength == tail.length) {
                endRecord = offset;
                break;
            }
        }
        if (endRecord < 0) throw new InvalidContentException("EPUB ZIP end record is missing or invalid");

        int diskNumber = readLittleEndianShort(tail, endRecord + 4);
        int centralDirectoryDisk = readLittleEndianShort(tail, endRecord + 6);
        int entriesOnDisk = readLittleEndianShort(tail, endRecord + 8);
        int totalEntries = readLittleEndianShort(tail, endRecord + 10);
        long centralDirectorySize = Integer.toUnsignedLong(readLittleEndianInt(tail, endRecord + 12));
        long centralDirectoryOffset = Integer.toUnsignedLong(readLittleEndianInt(tail, endRecord + 16));
        long endRecordPosition = fileSize - tailLength + endRecord;
        if (diskNumber != 0 || centralDirectoryDisk != 0 || entriesOnDisk != totalEntries
                || totalEntries == 0xffff || centralDirectorySize == 0xffff_ffffL
                || centralDirectoryOffset == 0xffff_ffffL || totalEntries != localEntryCount
                || totalEntries > properties.maxArchiveEntries()
                || totalEntries < 1 || centralDirectorySize < (long) totalEntries * 46
                || centralDirectoryOffset >= endRecordPosition
                || centralDirectoryOffset + centralDirectorySize > endRecordPosition) {
            throw new InvalidContentException("EPUB central directory is inconsistent or exceeds its entry limit");
        }

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            file.seek(centralDirectoryOffset);
            byte[] firstEntry = new byte[46];
            file.readFully(firstEntry);
            int flags = readLittleEndianShort(firstEntry, 8);
            int method = readLittleEndianShort(firstEntry, 10);
            int filenameLength = readLittleEndianShort(firstEntry, 28);
            int extraLength = readLittleEndianShort(firstEntry, 30);
            int commentLength = readLittleEndianShort(firstEntry, 32);
            long expectedCentralLength = 46L + filenameLength + extraLength + commentLength;
            long centralCrc = Integer.toUnsignedLong(readLittleEndianInt(firstEntry, 16));
            long centralCompressedSize = Integer.toUnsignedLong(readLittleEndianInt(firstEntry, 20));
            long centralExpandedSize = Integer.toUnsignedLong(readLittleEndianInt(firstEntry, 24));
            long localHeaderOffset = Integer.toUnsignedLong(readLittleEndianInt(firstEntry, 42));
            CRC32 expectedCrc = new CRC32();
            expectedCrc.update(EPUB_MIMETYPE);
            int encryptionFlags = (1 << 0) | (1 << 6) | (1 << 13);
            if (readLittleEndianInt(firstEntry, 0) != 0x02014b50
                    || (flags & encryptionFlags) != 0 || method != ZipEntry.STORED
                    || filenameLength != "mimetype".length() || extraLength != 0
                    || centralCompressedSize != EPUB_MIMETYPE.length
                    || centralExpandedSize != EPUB_MIMETYPE.length
                    || centralCrc != expectedCrc.getValue() || localHeaderOffset != 0
                    || expectedCentralLength > centralDirectorySize) {
                throw new InvalidContentException("EPUB central directory mimetype header is invalid");
            }
            byte[] name = new byte[filenameLength];
            file.readFully(name);
            if (!java.util.Arrays.equals(name, "mimetype".getBytes(StandardCharsets.UTF_8))) {
                throw new InvalidContentException("EPUB mimetype must be the first central directory entry");
            }
        }
    }

    private void validateEntryPath(ZipEntry entry, Set<String> paths, Set<String> files)
            throws InvalidContentException {
        String name = entry.getName();
        if (name == null || name.isBlank() || name.startsWith("/") || name.startsWith("\\")
                || name.contains("\\") || DRIVE_PREFIX.matcher(name).matches()
                || name.getBytes(StandardCharsets.UTF_8).length > 65535) {
            throw new InvalidContentException("EPUB entry path is invalid");
        }
        String withoutTrailingSlash = entry.isDirectory() && name.endsWith("/")
                ? name.substring(0, name.length() - 1) : name;
        String[] segments = withoutTrailingSlash.split("/", -1);
        if (segments.length == 0 || segments.length > properties.maxArchiveDepth()) {
            throw new InvalidContentException("EPUB entry path exceeds the depth limit");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.getBytes(StandardCharsets.UTF_8).length > 255) {
                throw new InvalidContentException("EPUB entry path contains an unsafe segment");
            }
            for (int index = 0; index < segment.length();) {
                int codePoint = segment.codePointAt(index);
                if (Character.isISOControl(codePoint)
                        || codePoint == '"' || codePoint == '*' || codePoint == ':'
                        || codePoint == '<' || codePoint == '>' || codePoint == '?'
                        || codePoint == '|' || codePoint == '\\'
                        || inPrivateUseArea(codePoint)
                        || codePoint >= 0xfdd0 && codePoint <= 0xfdef
                        || codePoint >= 0xfff0 && codePoint <= 0xffff
                        || (codePoint & 0xffff) == 0xfffe
                        || (codePoint & 0xffff) == 0xffff) {
                    throw new InvalidContentException("EPUB entry path contains control characters");
                }
                index += Character.charCount(codePoint);
            }
        }

        String normalized = canonicalPath(withoutTrailingSlash);
        if (!paths.add(normalized)) throw new InvalidContentException("EPUB archive contains duplicate paths");
        String[] normalizedSegments = normalized.split("/");
        StringBuilder ancestor = new StringBuilder();
        for (int index = 0; index < normalizedSegments.length - 1; index++) {
            if (index > 0) ancestor.append('/');
            ancestor.append(normalizedSegments[index]);
            if (files.contains(ancestor.toString())) {
                throw new InvalidContentException("EPUB file path conflicts with a directory path");
            }
        }
        if (!entry.isDirectory()) {
            String prefix = normalized + "/";
            if (files.stream().anyMatch(file -> file.startsWith(prefix))) {
                throw new InvalidContentException("EPUB file path conflicts with a directory path");
            }
            files.add(normalized);
        }
    }

    private static boolean inPrivateUseArea(int codePoint) {
        return codePoint >= 0xe000 && codePoint <= 0xf8ff
                || codePoint >= 0xf0000 && codePoint <= 0x10ffff;
    }

    private static String canonicalPath(String path) {
        String normalized = java.text.Normalizer.normalize(path, java.text.Normalizer.Form.NFC);
        return normalized.toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    private static boolean hasHtmlStart(CharSequence text) {
        String candidate = text.toString().replaceFirst("^\\uFEFF", "").stripLeading();
        candidate = candidate.replaceFirst("(?is)^<\\?xml[^?]*\\?>\\s*", "").stripLeading();
        while (candidate.startsWith("<!--")) {
            int end = candidate.indexOf("-->", 4);
            if (end < 0) return false;
            candidate = candidate.substring(end + 3).stripLeading();
        }
        return HTML_START.matcher(candidate).find();
    }

    private long deadline() throws InvalidContentException {
        long now = System.nanoTime();
        long timeout;
        try {
            timeout = properties.validationTimeout().toNanos();
        } catch (ArithmeticException exception) {
            throw new InvalidContentException("Content validation timeout is too large", exception);
        }
        try {
            return Math.addExact(now, timeout);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void checkDeadline(long deadline) throws InvalidContentException {
        if (System.nanoTime() - deadline >= 0) {
            throw new InvalidContentException("Content validation time limit exceeded");
        }
    }

    private static int readLittleEndianShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static int readLittleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | Byte.toUnsignedInt(bytes[offset + 3]) << 24;
    }

    private static String extension(String filename) {
        int dot = filename == null ? -1 : filename.lastIndexOf('.');
        return dot < 0 || dot == filename.length() - 1 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static final class InvalidContentException extends IOException {
        public InvalidContentException(String message) {
            super(message);
        }

        public InvalidContentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
