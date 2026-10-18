package com.grimtorrenter.app;

import com.grimtorrenter.engine.bencode.BencodeException;
import com.grimtorrenter.engine.engine.TorrentEngineException;
import com.grimtorrenter.engine.engine.TorrentEngineIoException;
import com.grimtorrenter.engine.magnet.MagnetLinkException;
import com.grimtorrenter.engine.metainfo.MetainfoException;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A request the engine can't honour is the client's 400; the engine failing its own disk I/O is
 * the server's 500. @QuarkusTest only so a JAX-RS runtime is there to build Responses. See
 * design_docs/0094. */
@QuarkusTest
@QuarkusTestResource(CleanDownloadsResource.class)
@QuarkusTestResource(TestSettingsResource.class)
class EngineExceptionMappersTest {

    private final EngineExceptionMappers mappers = new EngineExceptionMappers();

    @Test
    void malformedUploadsAndMagnetsAreA400() {
        assertEquals(400, mappers.bencode(new BencodeException("bad")).getStatus());
        assertEquals(400, mappers.metainfo(new MetainfoException("bad")).getStatus());
        assertEquals(Map.of("error", "bad"), mappers.magnetLink(new MagnetLinkException("bad")).getEntity());
    }

    @Test
    void badInputIsA400() {
        Response response = mappers.torrentEngine(new TorrentEngineException("No usable tracker"));

        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", "No usable tracker"), response.getEntity());
    }

    @Test
    void anEngineIoFailureIsA500() {
        Response response = mappers.torrentEngine(
                new TorrentEngineIoException("Could not delete x: busy", new IOException("busy")));

        assertEquals(500, response.getStatus());
        assertEquals(Map.of("error", "Could not delete x: busy"), response.getEntity());
    }
}
