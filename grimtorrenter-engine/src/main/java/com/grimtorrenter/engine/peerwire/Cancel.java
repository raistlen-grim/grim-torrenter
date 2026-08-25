package com.grimtorrenter.engine.peerwire;

public record Cancel(int index, int begin, int length) implements PeerMessage {
}
