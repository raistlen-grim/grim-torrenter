package com.grimtorrenter.app;

import com.grimtorrenter.engine.torrent.TorrentSession;

import java.util.List;

public record FileView(List<String> pathSegments, long length, long bytesDownloaded) {
    public static FileView from(TorrentSession.FileProgress file) {
        return new FileView(file.pathSegments(), file.length(), file.bytesDownloaded());
    }
}
