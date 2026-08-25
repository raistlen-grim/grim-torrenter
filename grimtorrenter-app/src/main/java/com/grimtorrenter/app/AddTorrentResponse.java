package com.grimtorrenter.app;

/** alreadyExisted lets the frontend tell "just added" apart from "you already had this" -
 * TorrentEngine.addTorrent() is idempotent and returns the same session either way, but
 * the two cases mean different things to the user uploading a file. */
public record AddTorrentResponse(TorrentView torrent, boolean alreadyExisted) {
}
