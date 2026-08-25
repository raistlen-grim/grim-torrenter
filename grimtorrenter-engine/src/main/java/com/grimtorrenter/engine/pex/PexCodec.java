package com.grimtorrenter.engine.pex;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BValue;
import com.grimtorrenter.engine.bencode.BencodeDecoder;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import com.grimtorrenter.engine.tracker.PeerAddress;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes/decodes {@link PexMessage}s to/from the bytes carried inside a BEP 10
 * {@code Extended} message's payload - the ut_pex analog of UtMetadataCodec. Unlike BEP
 * 5's get_peers "values" (a BList of separate 6-byte strings, see the dht package's own
 * CompactPeers), BEP 11's "added"/"dropped" are each a single BString of concatenated
 * 6-byte entries - a different compact-peer shape, not reusable from dht's own
 * package-private CompactPeers even if it weren't package-private. See design_docs/0040.
 */
public final class PexCodec {

    private static final String ADDED = "added";
    private static final String DROPPED = "dropped";
    private static final int ENTRY_LENGTH = 6;

    private PexCodec() {
    }

    public static byte[] encode(PexMessage message) {
        Map<BString, BValue> entries = new HashMap<>();
        entries.put(BString.of(ADDED), encodeAddresses(message.added()));
        entries.put(BString.of(DROPPED), encodeAddresses(message.dropped()));
        return BencodeEncoder.encode(new BDictionary(entries));
    }

    public static PexMessage decode(byte[] payload) {
        if (!(BencodeDecoder.decode(payload) instanceof BDictionary dict)) {
            throw new PexException("ut_pex message is not a dictionary");
        }
        return new PexMessage(decodeAddresses(dict, ADDED), decodeAddresses(dict, DROPPED));
    }

    private static BString encodeAddresses(List<PeerAddress> addresses) {
        byte[] out = new byte[addresses.size() * ENTRY_LENGTH];
        int offset = 0;
        for (PeerAddress address : addresses) {
            byte[] ip = requireIPv4(address.address());
            System.arraycopy(ip, 0, out, offset, 4);
            out[offset + 4] = (byte) (address.port() >> 8);
            out[offset + 5] = (byte) address.port();
            offset += ENTRY_LENGTH;
        }
        return BString.of(out);
    }

    /** Missing key decodes as empty rather than throwing - a peer that has nothing to
     * report in one direction (e.g. no drops yet) is expected to just omit that key. */
    private static List<PeerAddress> decodeAddresses(BDictionary dict, String key) {
        if (!(dict.get(key) instanceof BString entry)) {
            return List.of();
        }
        byte[] data = entry.bytes();
        if (data.length % ENTRY_LENGTH != 0) {
            throw new PexException("ut_pex '" + key + "' length " + data.length + " is not a multiple of " + ENTRY_LENGTH);
        }
        List<PeerAddress> addresses = new ArrayList<>(data.length / ENTRY_LENGTH);
        for (int offset = 0; offset < data.length; offset += ENTRY_LENGTH) {
            byte[] ip = {data[offset], data[offset + 1], data[offset + 2], data[offset + 3]};
            int port = ((data[offset + 4] & 0xFF) << 8) | (data[offset + 5] & 0xFF);
            try {
                addresses.add(new PeerAddress(InetAddress.getByAddress(ip), port));
            } catch (UnknownHostException e) {
                throw new PexException("Invalid address in ut_pex '" + key + "'");
            }
        }
        return addresses;
    }

    private static byte[] requireIPv4(InetAddress address) {
        if (!(address instanceof Inet4Address)) {
            throw new PexException("Only IPv4 peers are supported, got " + address);
        }
        return address.getAddress();
    }
}
