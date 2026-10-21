package com.githubci.intelligence.configuration;

import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.dynamodb.backfill-read-metadata", havingValue = "true")
public class AnalysisReadBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(AnalysisReadBackfill.class);
    private final WorkflowAnalysisRepository repository;

    public AnalysisReadBackfill(WorkflowAnalysisRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        log.info("Backfilled read metadata for {} workflow attempts", repository.backfillReadMetadata());
    }
}
