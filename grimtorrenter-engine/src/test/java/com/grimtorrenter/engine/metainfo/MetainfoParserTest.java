package com.grimtorrenter.engine.metainfo;

import com.grimtorrenter.engine.bencode.BDictionary;
import com.grimtorrenter.engine.bencode.BInteger;
import com.grimtorrenter.engine.bencode.BList;
import com.grimtorrenter.engine.bencode.BString;
import com.grimtorrenter.engine.bencode.BencodeEncoder;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MetainfoParserTest {

    private static byte[] sha1(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] fakePieceHashes(int count) {
        byte[] result = new byte[count * PieceHashes.HASH_LENGTH];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) i;
        }
        return result;
    }

    private static BDictionary minimalInfo() {
        return new BDictionary(Map.of(
                BString.of("name"), BString.of("x"),
                BString.of("piece length"), new BInteger(16384),
                BString.of("pieces"), BString.of(fakePieceHashes(1)),
                BString.of("length"), new BInteger(1)));
    }

    @Test
    void parsesSingleFileTorrent() {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of("movie.mkv"),
                BString.of("piece length"), new BInteger(16384),
                BString.of("pieces"), BString.of(fakePieceHashes(2)),
                BString.of("length"), new BInteger(32000)));

        BDictionary top = new BDictionary(Map.of(
                BString.of("announce"), BString.of("http://tracker.example/announce"),
                BString.of("info"), info));

        TorrentMetadata metadata = MetainfoParser.parse(BencodeEncoder.encode(top));

        SingleFileTorrent single = assertInstanceOf(SingleFileTorrent.class, metadata);
        assertEquals("movie.mkv", single.name());
        assertEquals(32000, single.length());
        assertEquals(32000, single.totalLength());
        assertEquals(16384, single.pieceLength());
        assertEquals(2, single.pieces().count());
        assertEquals("http://tracker.example/announce", single.announce());
        assertEquals(InfoHash.of(sha1(BencodeEncoder.encode(info))), single.infoHash());
    }

    /** TorrentMetadata.files() normalizes a single-file torrent (which has no "files" list
     * of its own on the wire) into a one-entry list - see design_docs/0031. */
    @Test
    void singleFileTorrentFilesSynthesizesOneEntry() {
        BDictionary top = new BDictionary(Map.of(
                BString.of("announce"), BString.of("http://tracker.example/announce"),
                BString.of("info"), new BDictionary(Map.of(
                        BString.of("name"), BString.of("movie.mkv"),
                        BString.of("piece length"), new BInteger(16384),
                        BString.of("pieces"), BString.of(fakePieceHashes(2)),
                        BString.of("length"), new BInteger(32000)))));

        TorrentMetadata metadata = MetainfoParser.parse(BencodeEncoder.encode(top));

        assertEquals(List.of(new TorrentFile(List.of("movie.mkv"), 32000)), metadata.files());
    }

    @Test
    void parsesMultiFileTorrent() {
        BDictionary fileA = new BDictionary(Map.of(
                BString.of("length"), new BInteger(100),
                BString.of("path"), new BList(List.of(BString.of("sub"), BString.of("a.txt")))));
        BDictionary fileB = new BDictionary(Map.of(
                BString.of("length"), new BInteger(200),
                BString.of("path"), new BList(List.of(BString.of("b.txt")))));

        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of("my-torrent"),
                BString.of("piece length"), new BInteger(16384),
                BString.of("pieces"), BString.of(fakePieceHashes(1)),
                BString.of("files"), new BList(List.of(fileA, fileB))));

        BDictionary top = new BDictionary(Map.of(BString.of("info"), info));

        TorrentMetadata metadata = MetainfoParser.parse(BencodeEncoder.encode(top));

        MultiFileTorrent multi = assertInstanceOf(MultiFileTorrent.class, metadata);
        assertEquals("my-torrent", multi.name());
        assertEquals(300, multi.totalLength());
        assertEquals(List.of(List.of("sub", "a.txt"), List.of("b.txt")),
                multi.files().stream().map(TorrentFile::pathSegments).toList());
    }

    @Test
    void parsesAnnounceListTiers() {
        BList tier1 = new BList(List.of(BString.of("http://a"), BString.of("http://b")));
        BList tier2 = new BList(List.of(BString.of("http://c")));
        BDictionary top = new BDictionary(Map.of(
                BString.of("info"), minimalInfo(),
                BString.of("announce-list"), new BList(List.of(tier1, tier2))));

        TorrentMetadata metadata = MetainfoParser.parse(BencodeEncoder.encode(top));

        assertEquals(List.of(List.of("http://a", "http://b"), List.of("http://c")),
                metadata.announceList());
    }

    @Test
    void announceIsNullWhenAbsent() {
        BDictionary top = new BDictionary(Map.of(BString.of("info"), minimalInfo()));

        TorrentMetadata metadata = MetainfoParser.parse(BencodeEncoder.encode(top));

        assertEquals(null, metadata.announce());
        assertEquals(List.of(), metadata.announceList());
    }

    @Test
    void rejectsMissingInfoDictionary() {
        BDictionary top = new BDictionary(Map.of(BString.of("announce"), BString.of("http://x")));
        byte[] bytes = BencodeEncoder.encode(top);
        assertThrows(MetainfoException.class, () -> MetainfoParser.parse(bytes));
    }

    @Test
    void rejectsPiecesLengthNotMultipleOf20() {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of("x"),
                BString.of("piece length"), new BInteger(16384),
                BString.of("pieces"), BString.of(new byte[]{1, 2, 3}),
                BString.of("length"), new BInteger(1)));
        BDictionary top = new BDictionary(Map.of(BString.of("info"), info));
        byte[] bytes = BencodeEncoder.encode(top);

        assertThrows(MetainfoException.class, () -> MetainfoParser.parse(bytes));
    }

    @Test
    void rejectsSingleFileTorrentMissingLength() {
        BDictionary info = new BDictionary(Map.of(
                BString.of("name"), BString.of("x"),
                BString.of("piece length"), new BInteger(16384),
                BString.of("pieces"), BString.of(fakePieceHashes(1))));
        BDictionary top = new BDictionary(Map.of(BString.of("info"), info));
        byte[] bytes = BencodeEncoder.encode(top);

        assertThrows(MetainfoException.class, () -> MetainfoParser.parse(bytes));
    }
}
