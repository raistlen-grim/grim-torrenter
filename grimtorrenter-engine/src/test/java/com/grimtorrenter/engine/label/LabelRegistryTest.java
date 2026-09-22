package com.grimtorrenter.engine.label;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0077. */
class LabelRegistryTest {

    @Test
    void createTrimsTheNameAssignsAUniqueIdAndKeepsInsertionOrder(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);

        Label movies = registry.create("  Movies ");
        Label music = registry.create("Music");

        assertEquals("Movies", movies.name());
        assertNotEquals(movies.id(), music.id());
        assertEquals(List.of(movies, music), registry.list());
        assertTrue(registry.exists(movies.id()));
        assertEquals(movies, registry.get(movies.id()).orElseThrow());
    }

    @Test
    void labelsSurviveAReloadFromDisk(@TempDir Path dir) {
        LabelRegistry first = new LabelRegistry(dir);
        Label movies = first.create("Movies");
        Label music = first.create("Music");

        assertEquals(List.of(movies, music), new LabelRegistry(dir).list());
    }

    @Test
    void renameChangesOnlyTheNameAndKeepsTheIdAndPosition(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);
        Label movies = registry.create("Movies");
        Label music = registry.create("Music");

        Label renamed = registry.rename(movies.id(), "Films");

        assertEquals(new Label(movies.id(), "Films"), renamed);
        assertEquals(List.of(renamed, music), registry.list());
        assertEquals(List.of(renamed, music), new LabelRegistry(dir).list());
    }

    @Test
    void aLabelCanBeRenamedToADifferentCaseOfItsOwnName(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);
        Label movies = registry.create("movies");

        assertEquals("Movies", registry.rename(movies.id(), "Movies").name());
    }

    @Test
    void duplicateNamesAreConflictsCaseInsensitivelyOnCreateAndRename(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);
        registry.create("Movies");
        Label music = registry.create("Music");

        assertThrows(LabelConflictException.class, () -> registry.create("movies"));
        assertThrows(LabelConflictException.class, () -> registry.rename(music.id(), "MOVIES"));
        assertEquals(2, registry.list().size());
    }

    @Test
    void invalidNamesAreRejected(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);

        assertThrows(IllegalArgumentException.class, () -> registry.create(null));
        assertThrows(IllegalArgumentException.class, () -> registry.create("   "));
        assertThrows(IllegalArgumentException.class,
                () -> registry.create("x".repeat(LabelRegistry.MAX_NAME_LENGTH + 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.create("two" + '\n' + "lines"));
        assertThrows(IllegalArgumentException.class, () -> registry.create("tab" + '\t' + "here"));
        assertTrue(registry.list().isEmpty());
    }

    @Test
    void aNameAtTheLengthLimitIsAccepted(@TempDir Path dir) {
        assertEquals(LabelRegistry.MAX_NAME_LENGTH,
                new LabelRegistry(dir).create("x".repeat(LabelRegistry.MAX_NAME_LENGTH)).name().length());
    }

    @Test
    void renamingAnUnknownLabelIsNotFound(@TempDir Path dir) {
        assertThrows(NoSuchElementException.class, () -> new LabelRegistry(dir).rename("nope", "Whatever"));
    }

    @Test
    void deleteRemovesThePersistedLabelAndReportsWhetherItExisted(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);
        Label movies = registry.create("Movies");
        Label music = registry.create("Music");

        assertTrue(registry.delete(movies.id()));
        assertFalse(registry.delete(movies.id()));

        assertEquals(List.of(music), registry.list());
        assertEquals(List.of(music), new LabelRegistry(dir).list());
    }

    @Test
    void aNameContainingAnEqualsSignRoundTrips(@TempDir Path dir) {
        Label created = new LabelRegistry(dir).create("a=b");

        assertEquals(created, new LabelRegistry(dir).list().get(0));
    }

    @Test
    void theLabelCountIsCapped(@TempDir Path dir) {
        LabelRegistry registry = new LabelRegistry(dir);
        for (int i = 0; i < LabelRegistry.MAX_LABELS; i++) {
            registry.create("label-" + i);
        }

        assertThrows(IllegalArgumentException.class, () -> registry.create("one-too-many"));
        assertEquals(LabelRegistry.MAX_LABELS, registry.list().size());
    }

    /** Skips lines with no '=', an empty id, an empty name, a control character, and a duplicate
     * id or name (case-insensitive) - the first valid line for each wins; a hand-edited file
     * never fails startup. */
    @Test
    void loadingAMalformedFileKeepsOnlyTheValidLines(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(LabelRegistry.FILENAME), String.join("\n",
                "id1=Movies",
                "no separator here",
                "=nameWithNoId",
                "id2=",
                "id3=bad" + (char) 1 + "name",
                "id1=DuplicateId",
                "id4=movies",
                "id5=Music") + "\n");

        assertEquals(List.of(new Label("id1", "Movies"), new Label("id5", "Music")),
                new LabelRegistry(dir).list());
    }

    @Test
    void aMissingFileIsAnEmptyList(@TempDir Path dir) {
        assertTrue(new LabelRegistry(dir.resolve("does-not-exist-yet")).list().isEmpty());
    }

    @Test
    void createMakesTheConfigDirectoryIfItDoesNotExistYet(@TempDir Path dir) {
        Path nested = dir.resolve("a").resolve("b");

        new LabelRegistry(nested).create("Movies");

        assertTrue(Files.exists(nested.resolve(LabelRegistry.FILENAME)));
    }
}
