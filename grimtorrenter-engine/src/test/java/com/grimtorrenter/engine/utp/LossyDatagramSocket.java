package com.grimtorrenter.engine.utp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.function.Predicate;

/**
 * A DatagramSocket that can deterministically drop outbound packets per a test-supplied policy -
 * new test infrastructure for design_docs/0074's slice 1 (no existing precedent for simulating
 * loss in this codebase, per that doc's own Testing section), overriding send() the same
 * legitimate way MulticastSocket extends DatagramSocket to specialize its behavior.
 *
 * <p>Decides per-packet from its own already-encoded wire bytes (the first bytes of the
 * datagram) via a Predicate, rather than tracking sequence numbers itself - this class has no
 * business knowing uTP's own wire format, it just needs a way for a test to say "drop the Nth
 * packet" or "drop every packet whose payload looks like X".
 */
final class LossyDatagramSocket extends DatagramSocket {

    private final Predicate<byte[]> dropIf;

    LossyDatagramSocket(Predicate<byte[]> dropIf) throws SocketException {
        this.dropIf = dropIf;
    }

    @Override
    public void send(DatagramPacket packet) throws IOException {
        byte[] wireBytes = Arrays.copyOfRange(packet.getData(), packet.getOffset(),
                packet.getOffset() + packet.getLength());
        if (dropIf.test(wireBytes)) {
            return; // Silently discarded - exactly what a real lost UDP datagram looks like to
            // the sender, which never learns whether a send() actually reached anyone.
        }
        super.send(packet);
    }
}
