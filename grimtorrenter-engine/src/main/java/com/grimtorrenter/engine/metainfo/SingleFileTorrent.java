package com.grimtorrenter.engine.metainfo;

import java.util.List;

public record SingleFileTorrent(
        String name,
        long length,
        long pieceLength,
        PieceHashes pieces,
        InfoHash infoHash,
        String announce,
        List<List<String>> announceList,
        boolean isPrivate
) implements TorrentMetadata {

    public SingleFileTorrent {
        announceList = List.copyOf(announceList);
    }

    /** Same as the eight-arg canonical constructor but defaults isPrivate to false - for
     * every caller that predates BEP 27 support and doesn't need it (tests, mainly). */
    public SingleFileTorrent(String name, long length, long pieceLength, PieceHashes pieces,
                              InfoHash infoHash, String announce, List<List<String>> announceList) {
        this(name, length, pieceLength, pieces, infoHash, announce, announceList, false);
    }
}
