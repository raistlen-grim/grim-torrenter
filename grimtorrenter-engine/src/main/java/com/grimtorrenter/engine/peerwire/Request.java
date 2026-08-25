package com.grimtorrenter.engine.peerwire;

public record Request(int index, int begin, int length) implements PeerMessage {
}
