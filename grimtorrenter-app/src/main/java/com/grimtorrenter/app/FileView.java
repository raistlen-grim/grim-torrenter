package com.grimtorrenter.app;

import com.grimtorrenter.engine.torrent.TorrentSession;

import java.util.List;

/** priority is the FilePriority name (SKIP/LOW/MEDIUM/HIGH) - see design_docs/0075. */
public record FileView(List<String> pathSegments, long length, long bytesDownloaded, String priority) {
    public static FileView from(TorrentSession.FileProgress file) {
        return new FileView(file.pathSegments(), file.length(), file.bytesDownloaded(), file.priority().name());
    }
}
