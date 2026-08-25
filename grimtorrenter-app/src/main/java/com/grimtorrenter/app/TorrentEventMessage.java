package com.grimtorrenter.app;

/** WebSocket envelope. type is "state-changed" (payload: single TorrentView) or
 * "snapshot" (payload: List&lt;TorrentView&gt;) - lets the client dispatch without guessing shape. */
public record TorrentEventMessage(String type, Object payload) {
}
