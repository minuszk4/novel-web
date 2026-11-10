package com.novelweb.library.imports;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "novel.imports.staging")
public record ImportStagingProperties(
        Path root,
        long maxUploadBytes,
        Duration uploadTargetTtl,
        Duration jobTtl) {

    public ImportStagingProperties {
        if (root == null) throw new IllegalArgumentException("Import staging root is required");
        if (maxUploadBytes < 1) throw new IllegalArgumentException("Import max upload size must be positive");
        if (uploadTargetTtl == null || uploadTargetTtl.isNegative() || uploadTargetTtl.isZero()) {
            throw new IllegalArgumentException("Import target lifetime must be positive");
        }
        if (jobTtl == null || jobTtl.compareTo(uploadTargetTtl) <= 0) {
            throw new IllegalArgumentException("Import job lifetime must exceed upload target lifetime");
        }
    }
}
