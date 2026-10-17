package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngineException;
import com.grimtorrenter.engine.engine.TorrentEngineIoException;
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
class TorrentEngineExceptionMapperTest {

    private final TorrentEngineExceptionMapper mapper = new TorrentEngineExceptionMapper();

    @Test
    void badInputIsA400() {
        Response response = mapper.toResponse(new TorrentEngineException("No usable tracker"));

        assertEquals(400, response.getStatus());
        assertEquals(Map.of("error", "No usable tracker"), response.getEntity());
    }

    @Test
    void anEngineIoFailureIsA500() {
        Response response = mapper.toResponse(
                new TorrentEngineIoException("Could not delete x: busy", new IOException("busy")));

        assertEquals(500, response.getStatus());
        assertEquals(Map.of("error", "Could not delete x: busy"), response.getEntity());
    }
}
