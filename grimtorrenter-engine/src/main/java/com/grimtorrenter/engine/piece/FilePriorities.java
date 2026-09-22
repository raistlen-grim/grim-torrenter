package com.grimtorrenter.engine.piece;

import java.util.Collections;
import java.util.List;

/** One FilePriority per file, in the torrent's own file order (TorrentMetadata.files()).
 * See design_docs/0075. */
public record FilePriorities(List<FilePriority> priorities) {

    public FilePriorities {
        priorities = List.copyOf(priorities);
    }

    public static FilePriorities allMedium(int fileCount) {
        return new FilePriorities(Collections.nCopies(fileCount, FilePriority.MEDIUM));
    }

    public FilePriority get(int fileIndex) {
        return fileIndex < priorities.size() ? priorities.get(fileIndex) : FilePriority.MEDIUM;
    }

    public boolean anyWanted() {
        return priorities.stream().anyMatch(FilePriority::isWanted);
    }
}
