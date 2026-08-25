package com.grimtorrenter.engine.metainfo;

import java.util.List;

public sealed interface TorrentMetadata permits SingleFileTorrent, MultiFileTorrent {

    String name();

    long pieceLength();

    PieceHashes pieces();

    InfoHash infoHash();

    /** May be null - not every torrent has a classic "announce" field. */
    String announce();

    /** Tiers per BEP 12; empty if the torrent has no announce-list. */
    List<List<String>> announceList();

    default long totalLength() {
        return switch (this) {
            case SingleFileTorrent s -> s.length();
            case MultiFileTorrent m -> m.files().stream().mapToLong(TorrentFile::length).sum();
        };
    }

    /** Normalizes both shapes into one file list - a single-file torrent has no "files"
     * list of its own on the wire, so this synthesizes a single TorrentFile from its
     * name/length. See design_docs/0031. */
    default List<TorrentFile> files() {
        return switch (this) {
            case SingleFileTorrent s -> List.of(new TorrentFile(List.of(s.name()), s.length()));
            case MultiFileTorrent m -> m.files();
        };
    }
}
