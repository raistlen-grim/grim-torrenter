package com.grimtorrenter.engine.metainfo;

import java.util.List;

public record MultiFileTorrent(
        String name,
        List<TorrentFile> files,
        long pieceLength,
        PieceHashes pieces,
        InfoHash infoHash,
        String announce,
        List<List<String>> announceList
) implements TorrentMetadata {

    public MultiFileTorrent {
        files = List.copyOf(files);
        announceList = List.copyOf(announceList);
    }
}
