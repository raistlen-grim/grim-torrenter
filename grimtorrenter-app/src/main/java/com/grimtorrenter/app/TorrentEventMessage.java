package com.grimtorrenter.app;

/** WebSocket envelope. type is "state-changed" (payload: single TorrentView), "snapshot"
 * (payload: List&lt;TorrentView&gt;), or "event" (payload: single LibraryEvent, design_docs/0055)
 * - lets the client dispatch without guessing shape. */
public record TorrentEventMessage(String type, Object payload) {
}
