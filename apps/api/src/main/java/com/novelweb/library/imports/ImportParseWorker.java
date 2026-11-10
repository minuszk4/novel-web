package com.novelweb.library.imports;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls the durable parser queue; each job is claimed and completed in separate short transactions. */
@Component
@Profile("postgres")
public class ImportParseWorker {

    private final ImportParseService parseService;

    public ImportParseWorker(ImportParseService parseService) {
        this.parseService = parseService;
    }

    @Scheduled(fixedDelayString = "${novel.imports.parser.poll-delay:1s}", scheduler = "importParseTaskScheduler")
    public void poll() {
        parseService.processNext();
    }
}
