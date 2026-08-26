package com.grimtorrenter.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grimtorrenter.engine.events.EventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import com.grimtorrenter.engine.settings.SettingsStore;
import io.quarkus.logging.Log;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * File-backed EventStore - one JSON-Lines file per calendar day (JVM default time zone) under
 * {@code {grimtorrenter.config-directory}/events/}, append-only (no read-modify-rewrite-whole-
 * file cost on every event, unlike JsonSettingsStore's swap-the-whole-value approach, which
 * doesn't fit an append-heavy log). Retention is enforced by deleting whole day-files older
 * than the live {@code Settings.eventLogRetentionDays} window - see prune()'s own Javadoc and
 * EventLogRetentionScheduler, which calls it hourly. @ApplicationScoped so every injector
 * (TorrentEngineProducer, TorrentEventListener, EventsResource) shares the exact same instance,
 * same reasoning as JsonSettingsStore. See design_docs/0055.
 */
@ApplicationScoped
public class JsonLinesEventStore implements EventStore {

    private static final String EVENTS_SUBDIRECTORY = "events";
    private static final String FILE_PREFIX = "events-";
    private static final String FILE_SUFFIX = ".jsonl";
    private static final DateTimeFormatter FILE_DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    @ConfigProperty(name = "grimtorrenter.config-directory", defaultValue = "config")
    String configDirectory;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    SettingsStore settingsStore;

    private Path eventsDirectory;

    /** Package-private (not private) so a test can call it directly after setting the above
     * fields itself, without going through CDI - matching JsonSettingsStore's own init(). */
    @PostConstruct
    void init() {
        eventsDirectory = Path.of(configDirectory).resolve(EVENTS_SUBDIRECTORY);
        try {
            Files.createDirectories(eventsDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create events directory " + eventsDirectory, e);
        }
        prune();
    }

    /** Appends to today's file, then broadcasts immediately over the same WebSocket
     * TorrentEventListener/TorrentSnapshotScheduler already use - this is the one place both
     * persistence and live push happen, so no caller needs to remember to do both. synchronized
     * so two concurrent events can't interleave their writes to the same day's file - not a hot
     * path (events fire on state transitions and engine decisions, not per-packet or
     * per-piece), so this doesn't reintroduce the synchronized-in-the-hot-path concern
     * design_docs/0050 resolved elsewhere. See design_docs/0055. */
    @Override
    public synchronized void record(LibraryEvent event) {
        Path file = fileFor(LocalDate.ofInstant(event.timestamp(), ZoneId.systemDefault()));
        try {
            String line = objectMapper.writeValueAsString(event) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not append event to " + file, e);
        }
        broadcast(event);
    }

    private void broadcast(LibraryEvent event) {
        try {
            TorrentWebSocket.broadcast(objectMapper.writeValueAsString(new TorrentEventMessage("event", event)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize library event", e);
        }
    }

    @Override
    public List<LibraryEvent> all() {
        return readAll(event -> true);
    }

    @Override
    public List<LibraryEvent> forTorrent(String infoHash) {
        return readAll(event -> infoHash.equals(event.infoHash()));
    }

    private List<LibraryEvent> readAll(Predicate<LibraryEvent> filter) {
        List<LibraryEvent> events = new ArrayList<>();
        for (Path file : dayFiles()) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    LibraryEvent event = objectMapper.readValue(line, LibraryEvent.class);
                    if (filter.test(event)) {
                        events.add(event);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not read events from " + file, e);
            }
        }
        events.sort(Comparator.comparing(LibraryEvent::timestamp).reversed());
        return events;
    }

    /** Deletes every day-file dated before today minus the currently configured retention
     * window - reads Settings live on every call rather than caching the window, so a change
     * to eventLogRetentionDays takes effect on this method's very next call (the next hourly
     * tick, or the one run at startup in init()), not synchronously. Package-private so
     * JsonLinesEventStoreTest can call it directly rather than waiting on
     * EventLogRetentionScheduler's real hourly tick, same spirit as TorrentEngine's
     * checkSeedingLimits(). See design_docs/0055 and design_docs/0051. */
    synchronized void prune() {
        LocalDate cutoff = LocalDate.now(ZoneId.systemDefault()).minusDays(settingsStore.current().eventLogRetentionDays());
        for (Path file : dayFiles()) {
            parseFileDate(file).filter(date -> date.isBefore(cutoff)).ifPresent(date -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not delete stale event log file " + file, e);
                }
            });
        }
    }

    private List<Path> dayFiles() {
        try (var files = Files.list(eventsDirectory)) {
            return files.filter(p -> p.getFileName().toString().endsWith(FILE_SUFFIX)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + eventsDirectory, e);
        }
    }

    private Path fileFor(LocalDate date) {
        return eventsDirectory.resolve(FILE_PREFIX + FILE_DATE_FORMAT.format(date) + FILE_SUFFIX);
    }

    private static Optional<LocalDate> parseFileDate(Path file) {
        String name = file.getFileName().toString();
        if (!name.startsWith(FILE_PREFIX) || !name.endsWith(FILE_SUFFIX)) {
            return Optional.empty();
        }
        String datePart = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
        try {
            return Optional.of(LocalDate.parse(datePart, FILE_DATE_FORMAT));
        } catch (DateTimeParseException e) {
            Log.debugf("Ignoring unrecognized file in events directory: %s", name);
            return Optional.empty();
        }
    }
}
