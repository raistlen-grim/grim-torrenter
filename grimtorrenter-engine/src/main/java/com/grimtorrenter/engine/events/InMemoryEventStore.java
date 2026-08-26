package com.grimtorrenter.engine.events;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A simple in-memory EventStore, no persistence and no retention pruning - the default where
 * no real (persisted, rolling-file) store is wired in, e.g. TorrentEngine's lower-arity
 * constructors, which exist purely so every pre-existing caller/test is unaffected by this
 * feature's addition. See design_docs/0055; mirrors InMemorySettingsStore's own role
 * (design_docs/0041).
 */
public final class InMemoryEventStore implements EventStore {

    private final List<LibraryEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(LibraryEvent event) {
        events.add(event);
    }

    @Override
    public synchronized List<LibraryEvent> all() {
        return sortedNewestFirst(events);
    }

    @Override
    public synchronized List<LibraryEvent> forTorrent(String infoHash) {
        return sortedNewestFirst(events.stream().filter(e -> infoHash.equals(e.infoHash())).toList());
    }

    private static List<LibraryEvent> sortedNewestFirst(List<LibraryEvent> events) {
        return events.stream().sorted(Comparator.comparing(LibraryEvent::timestamp).reversed()).toList();
    }
}
