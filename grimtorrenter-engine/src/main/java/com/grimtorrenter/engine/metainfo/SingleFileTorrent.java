package com.grimtorrenter.engine.metainfo;

import java.util.List;

public record SingleFileTorrent(
        String name,
        long length,
        long pieceLength,
        PieceHashes pieces,
        InfoHash infoHash,
        String announce,
        List<List<String>> announceList
) implements TorrentMetadata {

    public SingleFileTorrent {
        announceList = List.copyOf(announceList);
    }
}
