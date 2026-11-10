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
        Duration validationTimeout,
        int cleanupBatchSize,
        Duration cleanupDelay,
        int maxActiveJobsPerUser,
        long maxActiveDeclaredBytesPerUser,
        int createRateLimitCapacity,
        Duration createRateLimitRefillInterval,
        int uploadRateLimitCapacity,
        Duration uploadRateLimitRefillInterval,
        Duration rateLimitRetention) {

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
        if (cleanupBatchSize < 1 || cleanupBatchSize > 1000) {
            throw new IllegalArgumentException("Import staging cleanup batch size must be between 1 and 1000");
        }
        if (cleanupDelay == null || cleanupDelay.isNegative() || cleanupDelay.isZero()
                || cleanupDelay.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("Import staging cleanup delay must be positive and at most one day");
        }
        if (maxActiveJobsPerUser < 1 || maxActiveJobsPerUser > 100) {
            throw new IllegalArgumentException("Import active-job limit must be between 1 and 100");
        }
        if (maxActiveDeclaredBytesPerUser < maxUploadBytes) {
            throw new IllegalArgumentException("Per-user import byte limit must be at least the maximum upload size");
        }
        if (createRateLimitCapacity < 1 || createRateLimitCapacity > 100) {
            throw new IllegalArgumentException("Import create rate-limit capacity must be between 1 and 100");
        }
        validateRateLimitInterval(createRateLimitRefillInterval, "create");
        if (uploadRateLimitCapacity < 1 || uploadRateLimitCapacity > 100) {
            throw new IllegalArgumentException("Import upload rate-limit capacity must be between 1 and 100");
        }
        validateRateLimitInterval(uploadRateLimitRefillInterval, "upload");
        if (rateLimitRetention == null || rateLimitRetention.isNegative() || rateLimitRetention.isZero()
                || rateLimitRetention.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Import rate-limit retention must be positive and at most 30 days");
        }
    }

    private static void validateRateLimitInterval(Duration interval, String operation) {
        if (interval == null || interval.isNegative() || interval.isZero()
                || interval.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "Import " + operation + " rate-limit refill interval must be positive and at most one day");
        }
    }
}
