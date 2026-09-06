package com.grimtorrenter.engine.metainfo;

import java.util.List;

public record MultiFileTorrent(
        String name,
        List<TorrentFile> files,
        long pieceLength,
        PieceHashes pieces,
        InfoHash infoHash,
        String announce,
        List<List<String>> announceList,
        boolean isPrivate
) implements TorrentMetadata {

    public MultiFileTorrent {
        files = List.copyOf(files);
        announceList = List.copyOf(announceList);
    }

    /** Same as the eight-arg canonical constructor but defaults isPrivate to false - for
     * every caller that predates BEP 27 support and doesn't need it (tests, mainly). */
    public MultiFileTorrent(String name, List<TorrentFile> files, long pieceLength, PieceHashes pieces,
                             InfoHash infoHash, String announce, List<List<String>> announceList) {
        this(name, files, pieceLength, pieces, infoHash, announce, announceList, false);
    }
}
