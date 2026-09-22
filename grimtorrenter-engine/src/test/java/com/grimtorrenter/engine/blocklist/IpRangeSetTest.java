package com.grimtorrenter.engine.blocklist;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** design_docs/0078. */
class IpRangeSetTest {

    private static long ip(String dotted) throws Exception {
        return IpRangeSet.toLong(InetAddress.getByName(dotted).getAddress());
    }

    @Test
    void anEmptySetContainsNothing() throws Exception {
        assertTrue(IpRangeSet.EMPTY.isEmpty());
        assertFalse(IpRangeSet.EMPTY.contains(ip("1.2.3.4")));
        assertFalse(IpRangeSet.builder().build().contains(ip("1.2.3.4")));
    }

    @Test
    void containsIsInclusiveOfBothEndsAndNothingOutside() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(ip("10.0.0.5"), ip("10.0.0.9"));
        IpRangeSet set = builder.build();

        assertFalse(set.contains(ip("10.0.0.4")));
        assertTrue(set.contains(ip("10.0.0.5")));
        assertTrue(set.contains(ip("10.0.0.7")));
        assertTrue(set.contains(ip("10.0.0.9")));
        assertFalse(set.contains(ip("10.0.0.10")));
    }

    @Test
    void overlappingAndAdjacentRangesAreMergedIntoOne() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(ip("10.0.0.1"), ip("10.0.0.10"));
        builder.add(ip("10.0.0.5"), ip("10.0.0.20"));
        builder.add(ip("10.0.0.21"), ip("10.0.0.30"));
        IpRangeSet set = builder.build();

        assertEquals(1, set.size());
        assertTrue(set.contains(ip("10.0.0.1")));
        assertTrue(set.contains(ip("10.0.0.25")));
        assertTrue(set.contains(ip("10.0.0.30")));
        assertFalse(set.contains(ip("10.0.0.31")));
    }

    @Test
    void separateRangesStaySeparateAndAreAddedInAnyOrder() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(ip("50.0.0.0"), ip("50.0.0.255"));
        builder.add(ip("10.0.0.0"), ip("10.0.0.255"));
        builder.add(ip("30.0.0.0"), ip("30.0.0.255"));
        IpRangeSet set = builder.build();

        assertEquals(3, set.size());
        assertTrue(set.contains(ip("10.0.0.128")));
        assertTrue(set.contains(ip("30.0.0.128")));
        assertTrue(set.contains(ip("50.0.0.128")));
        assertFalse(set.contains(ip("20.0.0.1")));
    }

    /** Addresses from 128.0.0.0 up pack into a negative long - the regression this pins is them
     * sorting before every lower address and breaking both the merge and the binary search. */
    @Test
    void addressesAboveTheSignBitSortAndMergeCorrectlyAlongsideLowOnes() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(ip("200.1.1.0"), ip("200.1.1.255"));
        builder.add(ip("5.5.5.0"), ip("5.5.5.255"));
        builder.add(ip("200.1.0.0"), ip("200.1.0.255"));
        builder.add(ip("128.0.0.0"), ip("128.0.0.10"));
        builder.add(ip("255.255.255.0"), ip("255.255.255.255"));
        IpRangeSet set = builder.build();

        assertEquals(4, set.size(), "the two adjacent 200.1.x.x /24s merge; the rest stay apart");
        assertTrue(set.contains(ip("5.5.5.5")));
        assertTrue(set.contains(ip("128.0.0.0")));
        assertTrue(set.contains(ip("200.1.0.200")));
        assertTrue(set.contains(ip("200.1.1.200")));
        assertTrue(set.contains(ip("255.255.255.255")));
        assertFalse(set.contains(ip("200.1.2.0")));
        assertFalse(set.contains(ip("127.255.255.255")));
        assertFalse(set.contains(ip("128.0.0.11")));
    }

    @Test
    void aReversedRangeIsSwapped() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(ip("10.0.0.9"), ip("10.0.0.5"));

        assertTrue(builder.build().contains(ip("10.0.0.7")));
    }

    @Test
    void containsAnInetAddressAndNeverMatchesIpv6OrNull() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(0, 0xFFFFFFFFL);
        IpRangeSet set = builder.build();

        assertTrue(set.contains(InetAddress.getByName("8.8.8.8")));
        assertFalse(set.contains(InetAddress.getByName("::1")));
        assertFalse(set.contains((InetAddress) null));
    }

    @Test
    void theWholeAddressSpaceCanBeOneRange() throws Exception {
        IpRangeSet.Builder builder = IpRangeSet.builder();
        builder.add(0, 0xFFFFFFFFL);
        IpRangeSet set = builder.build();

        assertEquals(1, set.size());
        assertTrue(set.contains(0));
        assertTrue(set.contains(0xFFFFFFFFL));
    }
}
