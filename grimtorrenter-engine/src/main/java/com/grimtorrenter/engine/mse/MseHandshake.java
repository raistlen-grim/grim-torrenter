package com.grimtorrenter.engine.mse;

import com.grimtorrenter.engine.metainfo.InfoHash;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;

/**
 * Message Stream Encryption's negotiation protocol (design_docs/0052) - Diffie-Hellman key
 * exchange followed by an RC4-encrypted exchange of a verification constant, a crypto method
 * negotiation, and (for the outbound/initiator side) the torrent's info hash, obfuscated
 * rather than sent in the clear. Spec terms (S, SKEY, VC, crypto_provide/crypto_select,
 * PadA-D, IA) are used here exactly as the spec names them.
 *
 * <p>Operates entirely against InputStream/OutputStream, never Socket - same seam
 * PeerWireCodec already uses, for the same testability reason.
 */
public final class MseHandshake {

    private static final int MAX_PAD_LENGTH = 512;
    private static final byte[] VC = new byte[8];

    private static final int PLAINTEXT_BIT = 0x01;
    private static final int RC4_BIT = 0x02;

    private MseHandshake() {
    }

    /**
     * Negotiates MSE as the connection's initiator, already knowing which torrent (infoHash)
     * this connection is for. requireEncryption offers only RC4 in crypto_provide (a peer
     * that can't do RC4 simply can't complete this negotiation); otherwise both plaintext and
     * RC4 are offered, and whichever the peer selects is honored.
     */
    public static MseOutboundResult negotiateOutbound(InputStream in, OutputStream out, InfoHash infoHash,
                                                        boolean requireEncryption, SecureRandom random) throws IOException {
        DiffieHellman dh = new DiffieHellman(random);
        out.write(dh.publicKeyBytes());
        out.write(randomPad(random));
        out.flush();

        BigInteger peerPublicKey = DiffieHellman.publicKeyFromBytes(readExactly(in, DiffieHellman.PUBLIC_KEY_LENGTH_BYTES));
        byte[] sBytes = dh.sharedSecretBytes(peerPublicKey);
        byte[] infoHashBytes = infoHash.bytes();

        byte[] keyA = sha1(bytes("keyA"), sBytes, infoHashBytes);
        byte[] keyB = sha1(bytes("keyB"), sBytes, infoHashBytes);

        Rc4Cipher outgoingCipher = new Rc4Cipher(keyA);
        outgoingCipher.discard(Rc4Cipher.DISCARD_BYTES);

        byte[] req1 = sha1(bytes("req1"), sBytes);
        byte[] req2Xor3 = xor(sha1(bytes("req2"), infoHashBytes), sha1(bytes("req3"), sBytes));

        int cryptoProvide = requireEncryption ? RC4_BIT : (PLAINTEXT_BIT | RC4_BIT);
        byte[] padC = randomPad(random);
        byte[] body = concat(VC, int32(cryptoProvide), uint16(padC.length), padC, uint16(0));
        outgoingCipher.process(body);

        out.write(req1);
        out.write(req2Xor3);
        out.write(body);
        out.flush();

        SyncedDecryption sync = findVcByTrialDecryption(in, keyB);
        int cryptoSelect = readInt32BE(sync.header, 8);
        int padDLength = readUInt16BE(sync.header, 12);
        readAndDecrypt(in, sync.cipher, padDLength);

        boolean useRc4 = resolveSelectedMethod(cryptoSelect, requireEncryption);

        if (useRc4) {
            return new MseOutboundResult(new Rc4InputStream(in, sync.cipher), new Rc4OutputStream(out, outgoingCipher));
        }
        return new MseOutboundResult(in, out);
    }

    /**
     * Negotiates MSE as the connection's inbound acceptor. The incoming connection doesn't say
     * which torrent it's for in the clear - candidateInfoHashes is every torrent this engine
     * currently has active, tried in turn against the peer's obfuscated SKEY hash. Callers
     * must have already ruled out this being a plaintext handshake attempt (see PeerServer's
     * peek-and-branch) and in must be positioned at the very first byte of the peer's DH
     * public key, unconsumed.
     */
    public static MseInboundResult negotiateInbound(InputStream in, OutputStream out, Collection<InfoHash> candidateInfoHashes,
                                                      boolean requireEncryption, SecureRandom random) throws IOException {
        BigInteger peerPublicKey = DiffieHellman.publicKeyFromBytes(readExactly(in, DiffieHellman.PUBLIC_KEY_LENGTH_BYTES));
        DiffieHellman dh = new DiffieHellman(random);
        byte[] sBytes = dh.sharedSecretBytes(peerPublicKey);

        out.write(dh.publicKeyBytes());
        out.write(randomPad(random));
        out.flush();

        byte[] req1 = sha1(bytes("req1"), sBytes);
        findLiteralMarker(in, req1);

        byte[] receivedReq2Xor3 = readExactly(in, 20);
        byte[] req3 = sha1(bytes("req3"), sBytes);
        InfoHash matched = matchInfoHash(candidateInfoHashes, receivedReq2Xor3, req3);
        byte[] infoHashBytes = matched.bytes();

        byte[] keyA = sha1(bytes("keyA"), sBytes, infoHashBytes);
        byte[] keyB = sha1(bytes("keyB"), sBytes, infoHashBytes);

        Rc4Cipher incomingCipher = new Rc4Cipher(keyA);
        incomingCipher.discard(Rc4Cipher.DISCARD_BYTES);

        byte[] header = readAndDecrypt(in, incomingCipher, 14);
        if (!Arrays.equals(VC, Arrays.copyOfRange(header, 0, 8))) {
            throw new MseNegotiationException("MSE verification constant mismatch after SKEY match");
        }
        int cryptoProvide = readInt32BE(header, 8);
        int padCLength = readUInt16BE(header, 12);
        readAndDecrypt(in, incomingCipher, padCLength);
        int iaLength = readUInt16BE(readAndDecrypt(in, incomingCipher, 2), 0);
        byte[] ia = readAndDecrypt(in, incomingCipher, iaLength);

        boolean useRc4 = resolveSelectedMethodAsReceiver(cryptoProvide, requireEncryption);
        int cryptoSelect = useRc4 ? RC4_BIT : PLAINTEXT_BIT;

        Rc4Cipher outgoingCipher = new Rc4Cipher(keyB);
        outgoingCipher.discard(Rc4Cipher.DISCARD_BYTES);
        byte[] padD = randomPad(random);
        byte[] reply = concat(VC, int32(cryptoSelect), uint16(padD.length), padD);
        outgoingCipher.process(reply);
        out.write(reply);
        out.flush();

        InputStream iaPrefix = new ByteArrayInputStream(ia);
        if (useRc4) {
            InputStream resultIn = ia.length == 0 ? new Rc4InputStream(in, incomingCipher)
                    : new SequenceInputStream(iaPrefix, new Rc4InputStream(in, incomingCipher));
            return new MseInboundResult(matched, resultIn, new Rc4OutputStream(out, outgoingCipher));
        }
        InputStream resultIn = ia.length == 0 ? in : new SequenceInputStream(iaPrefix, in);
        return new MseInboundResult(matched, resultIn, out);
    }

    /** true means RC4 was selected, false means plaintext. */
    private static boolean resolveSelectedMethod(int cryptoSelect, boolean requireEncryption) throws MseNegotiationException {
        boolean rc4Selected = (cryptoSelect & RC4_BIT) != 0;
        boolean plaintextSelected = (cryptoSelect & PLAINTEXT_BIT) != 0;
        if (rc4Selected) {
            return true;
        }
        if (plaintextSelected && !requireEncryption) {
            return false;
        }
        throw new MseNegotiationException("peer selected an unusable MSE crypto method: " + cryptoSelect);
    }

    /** Our own choice as the receiver, from what the peer's crypto_provide offered - see
     * design_docs/0052: prefer RC4 whenever it's on offer, matching REQUIRED's demand for it
     * and PREFERRED's whole point when it's available either way. */
    private static boolean resolveSelectedMethodAsReceiver(int cryptoProvide, boolean requireEncryption) throws MseNegotiationException {
        boolean rc4Offered = (cryptoProvide & RC4_BIT) != 0;
        boolean plaintextOffered = (cryptoProvide & PLAINTEXT_BIT) != 0;
        if (rc4Offered) {
            return true;
        }
        if (plaintextOffered && !requireEncryption) {
            return false;
        }
        throw new MseNegotiationException("peer's crypto_provide offered no usable method: " + cryptoProvide);
    }

    private static InfoHash matchInfoHash(Collection<InfoHash> candidates, byte[] receivedReq2Xor3, byte[] req3)
            throws MseNegotiationException {
        for (InfoHash candidate : candidates) {
            byte[] candidateReq2 = sha1(bytes("req2"), candidate.bytes());
            if (Arrays.equals(xor(candidateReq2, req3), receivedReq2Xor3)) {
                return candidate;
            }
        }
        throw new MseNegotiationException("no active torrent's info hash matched this MSE connection's SKEY");
    }

    /** Finds HASH('req1', S) - sent in the clear - within the stream immediately after PadA,
     * reading one byte at a time and testing a sliding 20-byte window against it, up to
     * MAX_PAD_LENGTH bytes of search room (PadA's spec-bounded maximum length). Leaves the
     * stream positioned exactly after the matched marker. */
    private static void findLiteralMarker(InputStream in, byte[] marker) throws IOException {
        byte[] window = new byte[marker.length];
        int filled = 0;
        for (int scanned = 0; scanned <= MAX_PAD_LENGTH + marker.length; scanned++) {
            int b = readByteOrThrow(in);
            if (filled < window.length) {
                window[filled++] = (byte) b;
            } else {
                System.arraycopy(window, 1, window, 0, window.length - 1);
                window[window.length - 1] = (byte) b;
            }
            if (filled == window.length && Arrays.equals(window, marker)) {
                return;
            }
        }
        throw new MseNegotiationException("no MSE synchronization point found (not an MSE peer, or a garbled stream)");
    }

    private record SyncedDecryption(Rc4Cipher cipher, byte[] header) {
    }

    /** Finds the start of the peer's encrypted VC (ENCRYPT(VC, ...)) within the stream
     * immediately after PadB, by trial-decrypting each candidate offset (a throwaway cipher
     * per candidate, since each hypothesis is "the ciphertext starts here" - the keystream
     * itself always starts at position 0 of whatever's actually being decrypted) and checking
     * for an 8-byte match against VC. Unlike findLiteralMarker, VC is never sent unencrypted
     * for this direction, so a literal scan isn't possible here - see design_docs/0052.
     * Bounded to MAX_PAD_LENGTH candidates, matching PadB's spec-bounded maximum length.
     * Returns the real (non-throwaway) cipher, already advanced past decrypting the 14-byte
     * VC+crypto_select+len(padD) header, plus that decrypted header. */
    private static SyncedDecryption findVcByTrialDecryption(InputStream in, byte[] key) throws IOException {
        byte[] buffer = new byte[MAX_PAD_LENGTH + 8];
        int filled = 0;
        int syncOffset = -1;
        while (filled < buffer.length) {
            buffer[filled++] = (byte) readByteOrThrow(in);
            if (filled < 8) {
                continue;
            }
            int candidate = filled - 8;
            Rc4Cipher trial = new Rc4Cipher(key);
            trial.discard(Rc4Cipher.DISCARD_BYTES);
            byte[] window = Arrays.copyOfRange(buffer, candidate, candidate + 8);
            trial.process(window);
            if (Arrays.equals(VC, window)) {
                syncOffset = candidate;
                break;
            }
        }
        if (syncOffset < 0) {
            throw new MseNegotiationException("no MSE synchronization point found (not an MSE peer, or a garbled stream)");
        }

        Rc4Cipher real = new Rc4Cipher(key);
        real.discard(Rc4Cipher.DISCARD_BYTES);
        byte[] header = Arrays.copyOfRange(buffer, syncOffset, filled);
        real.process(header);
        if (header.length < 14) {
            byte[] rest = readExactly(in, 14 - header.length);
            real.process(rest);
            header = concat(header, rest);
        }
        if (!Arrays.equals(VC, Arrays.copyOfRange(header, 0, 8))) {
            throw new MseNegotiationException("MSE verification constant mismatch at the located sync point");
        }
        return new SyncedDecryption(real, header);
    }

    private static byte[] readAndDecrypt(InputStream in, Rc4Cipher cipher, int length) throws IOException {
        byte[] data = readExactly(in, length);
        cipher.process(data);
        return data;
    }

    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] data = in.readNBytes(length);
        if (data.length != length) {
            throw new EOFException("Expected " + length + " bytes during MSE negotiation, got " + data.length);
        }
        return data;
    }

    private static int readByteOrThrow(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException("Stream closed during MSE negotiation");
        }
        return b;
    }

    private static byte[] randomPad(SecureRandom random) {
        byte[] pad = new byte[random.nextInt(MAX_PAD_LENGTH + 1)];
        random.nextBytes(pad);
        return pad;
    }

    private static int readInt32BE(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).getInt();
    }

    private static int readUInt16BE(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 2).getShort() & 0xFFFF;
    }

    private static byte[] int32(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    private static byte[] uint16(int value) {
        return ByteBuffer.allocate(2).putShort((short) value).array();
    }

    private static byte[] xor(byte[] a, byte[] b) {
        byte[] result = new byte[a.length];
        for (int i = 0; i < a.length; i++) {
            result[i] = (byte) (a[i] ^ b[i]);
        }
        return result;
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) {
            total += part.length;
        }
        byte[] result = new byte[total];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    private static byte[] bytes(String literal) {
        return literal.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] sha1(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available on this JVM", e);
        }
    }
}
