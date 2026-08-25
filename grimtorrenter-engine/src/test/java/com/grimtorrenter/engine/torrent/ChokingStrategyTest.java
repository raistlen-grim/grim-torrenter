package com.grimtorrenter.engine.torrent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ChokingStrategyTest {

    @Test
    void unchokesEveryoneWhenAtOrUnderCap() {
        List<String> peers = List.of("a", "b", "c");
        assertEquals(Set.of("a", "b", "c"), ChokingStrategy.selectToUnchoke(peers, 4, 0));
        assertEquals(Set.of("a", "b", "c"), ChokingStrategy.selectToUnchoke(peers, 3, 0));
    }

    @Test
    void capsAtMaxUnchokedWhenOverCap() {
        List<String> peers = List.of("a", "b", "c", "d", "e");
        assertEquals(2, ChokingStrategy.selectToUnchoke(peers, 2, 0).size());
    }

    @Test
    void rotationChangesWhichPeersAreSelectedOverCap() {
        List<String> peers = List.of("a", "b", "c", "d", "e");
        Set<String> first = ChokingStrategy.selectToUnchoke(peers, 2, 0);
        Set<String> second = ChokingStrategy.selectToUnchoke(peers, 2, 2);
        assertNotEquals(first, second);
    }

    @Test
    void handlesEmptyList() {
        assertEquals(Set.of(), ChokingStrategy.selectToUnchoke(List.of(), 4, 0));
    }
}
