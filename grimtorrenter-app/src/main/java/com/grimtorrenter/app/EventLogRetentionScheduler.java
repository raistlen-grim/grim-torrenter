package com.grimtorrenter.app;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Hourly bound on the library event log's on-disk size (design_docs/0055): deletes any day-file
 * older than the live Settings.eventLogRetentionDays window. Also run once at startup via
 * JsonLinesEventStore's own @PostConstruct, so a period the app was down for doesn't leave
 * stale files sitting past their window until the next event happens to be recorded. Cheap - a
 * single directory listing plus a filename-date comparison per tick, no new unbounded growth.
 * See design_docs/0051.
 */
@ApplicationScoped
public class EventLogRetentionScheduler {

    @Inject
    JsonLinesEventStore eventStore;

    @Scheduled(every = "1h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pruneEventLog() {
        eventStore.prune();
    }
}
