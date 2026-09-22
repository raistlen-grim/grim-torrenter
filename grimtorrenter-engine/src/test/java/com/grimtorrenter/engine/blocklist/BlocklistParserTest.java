package com.grimtorrenter.engine.blocklist;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0078. */
class BlocklistParserTest {

    private static BlocklistParser.Result parse(String text) throws IOException {
        return BlocklistParser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)));
    }

    private static boolean contains(IpRangeSet set, String dotted) throws Exception {
        return set.contains(InetAddress.getByName(dotted));
    }

    @Test
    void parsesPeerGuardianP2pLines() throws Exception {
        BlocklistParser.Result result = parse("Some Org:1.2.3.4-1.2.3.9\nAnother:10.0.0.0-10.0.0.255\n");

        assertEquals(2, result.ranges().size());
        assertTrue(contains(result.ranges(), "1.2.3.6"));
        assertTrue(contains(result.ranges(), "10.0.0.200"));
        assertFalse(contains(result.ranges(), "1.2.3.10"));
        assertEquals(0, result.skipped());
    }

    /** The name can contain colons; the range is whatever follows the last one. */
    @Test
    void aP2pNameContainingColonsStillParses() throws Exception {
        BlocklistParser.Result result = parse("Level1: Bad: Actor:20.0.0.1-20.0.0.5\n");

        assertTrue(contains(result.ranges(), "20.0.0.3"));
        assertEquals(0, result.skipped());
    }

    @Test
    void parsesEmuleDatLinesIncludingLeadingZerosAndTheTrailingLevelAndName() throws Exception {
        BlocklistParser.Result result = parse("001.002.003.004 - 001.002.003.009 , 100 , Bad Actor\n");

        assertTrue(contains(result.ranges(), "1.2.3.4"));
        assertTrue(contains(result.ranges(), "1.2.3.9"));
        assertFalse(contains(result.ranges(), "1.2.3.10"));
    }

    @Test
    void parsesPlainRangesCidrAndSingleAddresses() throws Exception {
        BlocklistParser.Result result = parse("5.5.5.1-5.5.5.3\n6.6.6.0/24\n7.7.7.7\n");

        assertTrue(contains(result.ranges(), "5.5.5.2"));
        assertTrue(contains(result.ranges(), "6.6.6.0"));
        assertTrue(contains(result.ranges(), "6.6.6.255"));
        assertFalse(contains(result.ranges(), "6.6.7.0"));
        assertTrue(contains(result.ranges(), "7.7.7.7"));
        assertFalse(contains(result.ranges(), "7.7.7.8"));
    }

    @Test
    void cidrIsAlignedToItsPrefixAndZeroCoversEverything() throws Exception {
        BlocklistParser.Result unaligned = parse("10.1.2.99/24\n");
        assertTrue(contains(unaligned.ranges(), "10.1.2.0"));
        assertTrue(contains(unaligned.ranges(), "10.1.2.255"));

        BlocklistParser.Result everything = parse("0.0.0.0/0\n");
        assertTrue(contains(everything.ranges(), "1.1.1.1"));
        assertTrue(contains(everything.ranges(), "250.250.250.250"));

        BlocklistParser.Result single = parse("9.9.9.9/32\n");
        assertTrue(contains(single.ranges(), "9.9.9.9"));
        assertFalse(contains(single.ranges(), "9.9.9.10"));
    }

    @Test
    void commentsAndBlankLinesAreIgnoredAndUnparseableOnesAreCountedAsSkipped() throws Exception {
        BlocklistParser.Result result = parse(String.join("\n",
                "# a comment",
                "// another comment",
                "",
                "   ",
                "1.1.1.1",
                "not an address at all",
                "999.1.1.1",
                "1.1.1.1-999.1.1.1",
                "8.8.8.0/33",
                "::1",
                "2.2.2.2") + "\n");

        assertEquals(7, result.lines(), "seven non-comment lines were seen");
        assertEquals(5, result.skipped());
        assertTrue(contains(result.ranges(), "1.1.1.1"));
        assertTrue(contains(result.ranges(), "2.2.2.2"));
        assertEquals(2, result.ranges().size());
    }

    @Test
    void anEmptyStreamIsAnEmptyList() throws Exception {
        BlocklistParser.Result result = parse("");

        assertTrue(result.ranges().isEmpty());
        assertEquals(0, result.lines());
    }

    @Test
    void gzipInputIsDetectedAndDecompressedTransparently() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write("Bad:30.0.0.0-30.0.0.255\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        BlocklistParser.Result result = BlocklistParser.parse(new ByteArrayInputStream(compressed.toByteArray()));

        assertTrue(contains(result.ranges(), "30.0.0.77"));
    }

    @Test
    void aTooLongLineIsSkippedRatherThanParsed() throws Exception {
        BlocklistParser.Result result = parse("x".repeat(5000) + ":1.1.1.1-1.1.1.2\n3.3.3.3\n");

        assertEquals(1, result.skipped());
        assertFalse(contains(result.ranges(), "1.1.1.1"));
        assertTrue(contains(result.ranges(), "3.3.3.3"));
    }

    /** A decompression bomb: a tiny gzip that expands past the limit fails the whole load rather
     * than filling memory. */
    @Test
    void decompressedInputBeyondTheByteLimitIsRefused() throws Exception {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            byte[] block = "1.1.1.1\n".repeat(1024).getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < 64; i++) {
                gzip.write(block);
            }
        }

        assertThrows(IOException.class, () -> BlocklistParser.parse(
                new ByteArrayInputStream(compressed.toByteArray()), BlocklistParser.MAX_ENTRIES, 10_000));
    }

    @Test
    void moreEntriesThanTheLimitRefusesTheWholeListInsteadOfTruncatingIt() {
        String text = "1.1.1.1\n2.2.2.2\n3.3.3.3\n4.4.4.4\n";

        assertThrows(IOException.class, () -> BlocklistParser.parse(
                new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1)), 3,
                BlocklistParser.MAX_DECOMPRESSED_BYTES));
    }

    @Test
    void aCorruptGzipStreamIsAnIoException() {
        byte[] notReallyGzip = {0x1f, (byte) 0x8b, 0x00, 0x01, 0x02, 0x03};

        assertThrows(IOException.class, () -> BlocklistParser.parse(new ByteArrayInputStream(notReallyGzip)));
    }
}
