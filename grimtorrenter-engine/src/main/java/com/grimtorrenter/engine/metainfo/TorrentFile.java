package com.grimtorrenter.engine.metainfo;

import java.util.List;

/**
 * One entry from a multi-file torrent's "files" list. pathSegments is the
 * raw path component list from the info dict (e.g. ["sub", "a.txt"]) -
 * joining them with a platform path separator is a storage-layer concern.
 */
public record TorrentFile(List<String> pathSegments, long length) {

    public TorrentFile {
        pathSegments = List.copyOf(pathSegments);
    }
}
