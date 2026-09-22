package com.grimtorrenter.engine.label;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The engine-wide managed list of labels (design_docs/0077). Persisted in one plain-text file,
 * {@code id=name} per line (name split on the first '=' so it may itself contain one), written
 * atomically; loading is tolerant - a malformed or duplicate line is skipped, a missing file is
 * an empty list.
 *
 * <p>Reads are lock-free against an immutable snapshot; mutations take a lock (a cold path - a
 * user editing labels) and replace the snapshot only after the file was written, so a failed
 * write leaves both memory and disk unchanged.
 */
public final class LabelRegistry {

    public static final String FILENAME = ".grimtorrenter-labels";
    public static final int MAX_LABELS = 200;
    public static final int MAX_NAME_LENGTH = 32;

    private final Path file;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile List<Label> labels;

    public LabelRegistry(Path configDirectory) {
        this.file = configDirectory.resolve(FILENAME);
        this.labels = load(file);
    }

    public List<Label> list() {
        return labels;
    }

    public Optional<Label> get(String id) {
        return labels.stream().filter(label -> label.id().equals(id)).findFirst();
    }

    public boolean exists(String id) {
        return get(id).isPresent();
    }

    /** @throws IllegalArgumentException for an invalid name or when the label cap is reached
     * @throws LabelConflictException if the name is already taken */
    public Label create(String name) {
        String cleaned = validateName(name);
        lock.lock();
        try {
            if (labels.size() >= MAX_LABELS) {
                throw new IllegalArgumentException("At most " + MAX_LABELS + " labels are allowed");
            }
            requireNameFree(cleaned, null);
            Label created = new Label(UUID.randomUUID().toString(), cleaned);
            List<Label> updated = new ArrayList<>(labels);
            updated.add(created);
            persistThenPublish(updated);
            return created;
        } finally {
            lock.unlock();
        }
    }

    /** @throws NoSuchElementException if no such label
     * @throws IllegalArgumentException for an invalid name
     * @throws LabelConflictException if another label already has the name */
    public Label rename(String id, String name) {
        String cleaned = validateName(name);
        lock.lock();
        try {
            requireExists(id);
            requireNameFree(cleaned, id);
            Label renamed = new Label(id, cleaned);
            List<Label> updated = new ArrayList<>(labels.size());
            for (Label label : labels) {
                updated.add(label.id().equals(id) ? renamed : label);
            }
            persistThenPublish(updated);
            return renamed;
        } finally {
            lock.unlock();
        }
    }

    /** @return whether a label with that id existed */
    public boolean delete(String id) {
        lock.lock();
        try {
            if (!exists(id)) {
                return false;
            }
            List<Label> updated = new ArrayList<>(labels);
            updated.removeIf(label -> label.id().equals(id));
            persistThenPublish(updated);
            return true;
        } finally {
            lock.unlock();
        }
    }

    private void requireExists(String id) {
        if (!exists(id)) {
            throw new NoSuchElementException("No such label: " + id);
        }
    }

    private void requireNameFree(String name, String exceptId) {
        for (Label label : labels) {
            if (!label.id().equals(exceptId) && label.name().equalsIgnoreCase(name)) {
                throw new LabelConflictException("A label named \"" + label.name() + "\" already exists");
            }
        }
    }

    /** Trimmed, 1-MAX_NAME_LENGTH characters, no control characters - which also rules out a
     * newline forging a second id=name line in the file. */
    static String validateName(String name) {
        if (name == null) {
            throw new IllegalArgumentException("A label name is required");
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A label name is required");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("A label name can be at most " + MAX_NAME_LENGTH + " characters");
        }
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A label name can't contain control characters");
        }
        return trimmed;
    }

    private void persistThenPublish(List<Label> updated) {
        StringBuilder content = new StringBuilder();
        for (Label label : updated) {
            content.append(label.id()).append('=').append(label.name()).append('\n');
        }
        try {
            Path directory = file.getParent();
            Files.createDirectories(directory);
            Path temp = Files.createTempFile(directory, FILENAME, ".tmp");
            try {
                Files.writeString(temp, content.toString());
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not persist labels to " + file + ": " + e.getMessage(), e);
        }
        this.labels = List.copyOf(updated);
    }

    private static List<Label> load(Path file) {
        List<Label> loaded = new ArrayList<>();
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            for (String line : Files.readAllLines(file)) {
                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String id = line.substring(0, separator);
                String name;
                try {
                    name = validateName(line.substring(separator + 1));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                boolean duplicate = loaded.stream()
                        .anyMatch(label -> label.id().equals(id) || label.name().equalsIgnoreCase(name));
                if (!duplicate && loaded.size() < MAX_LABELS) {
                    loaded.add(new Label(id, name));
                }
            }
        } catch (IOException e) {
            return List.of();
        }
        return List.copyOf(loaded);
    }
}
