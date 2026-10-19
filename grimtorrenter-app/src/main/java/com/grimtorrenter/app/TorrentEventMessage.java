package com.grimtorrenter.app;

/** WebSocket envelope. type is "state-changed" (payload: single TorrentView), "snapshot"
 * (payload: List&lt;TorrentView&gt;), "event" (payload: single LibraryEvent, design_docs/0055),
 * "labels" (payload: the full List&lt;Label&gt;, sent on every snapshot tick - design_docs/0077), or
 * "transfer" (payload: TransferView, also on every snapshot tick - design_docs/0102) - lets the
 * client dispatch without guessing shape. */
public record TorrentEventMessage(String type, Object payload) {
}
