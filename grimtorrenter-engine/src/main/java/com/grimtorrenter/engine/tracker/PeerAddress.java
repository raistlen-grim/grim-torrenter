package com.grimtorrenter.engine.tracker;

import java.net.InetAddress;

public record PeerAddress(InetAddress address, int port) {
}
