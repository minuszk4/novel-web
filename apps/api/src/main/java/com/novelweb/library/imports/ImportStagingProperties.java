package com.novelweb.library.imports;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novel.imports.staging")
public record ImportStagingProperties(
        Path root,
        long maxUploadBytes,
        Duration uploadTargetTtl,
        Duration jobTtl,
        int maxArchiveEntries,
        long maxArchiveEntryBytes,
        long maxArchiveExpandedBytes,
        double maxArchiveCompressionRatio,
        int maxArchiveDepth,
        Duration validationTimeout) {

    public ImportStagingProperties {
        if (root == null) throw new IllegalArgumentException("Import staging root is required");
        if (maxUploadBytes < 1) throw new IllegalArgumentException("Import max upload size must be positive");
        if (uploadTargetTtl == null || uploadTargetTtl.isNegative() || uploadTargetTtl.isZero()) {
            throw new IllegalArgumentException("Import target lifetime must be positive");
        }
        if (jobTtl == null || jobTtl.compareTo(uploadTargetTtl) <= 0) {
            throw new IllegalArgumentException("Import job lifetime must exceed upload target lifetime");
        }
        if (maxArchiveEntries < 1 || maxArchiveEntryBytes < 1 || maxArchiveExpandedBytes < maxArchiveEntryBytes) {
            throw new IllegalArgumentException("Import archive count and expanded-byte limits must be positive");
        }
        if (!Double.isFinite(maxArchiveCompressionRatio) || maxArchiveCompressionRatio < 1.0) {
            throw new IllegalArgumentException("Import archive compression ratio must be finite and at least one");
        }
        if (maxArchiveDepth < 1) throw new IllegalArgumentException("Import archive path depth must be positive");
        if (validationTimeout == null || validationTimeout.isNegative() || validationTimeout.isZero()) {
            throw new IllegalArgumentException("Import content validation timeout must be positive");
        }
        if (validationTimeout.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("Import content validation timeout must not exceed one hour");
        }
    }
}
