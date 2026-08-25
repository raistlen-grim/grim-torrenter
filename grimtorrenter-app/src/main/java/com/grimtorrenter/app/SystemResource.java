package com.grimtorrenter.app;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** Global, torrent-independent host state - same "separate small resource" shape as
 * DhtResource, since this isn't scoped to any one torrent either. See design_docs/0043.
 *
 * <p>Reads grimtorrenter.download-directory directly (the same property
 * TorrentEngineProducer reads) rather than through TorrentEngine - it's deploy-time config
 * already safe to read from the app layer, so no new engine accessor was needed. */
@Path("/api/system")
public class SystemResource {

    @ConfigProperty(name = "grimtorrenter.download-directory", defaultValue = "downloads")
    String downloadDirectory;

    /** Creates the directory itself first rather than assuming TorrentEngine already has -
     * it only creates it as an incidental side effect of persisting a DHT node id marker
     * (TorrentEngine.loadOrGenerateDhtNodeId), which never runs at all when DHT is disabled
     * (the default in this project's own test suite, and a legitimate user setting in
     * production) and even then only on a first-ever boot, with failures swallowed. Real
     * bug caught by SystemResourceTest failing with NoSuchFileException against a fresh
     * target/test-downloads before this fix. */
    @GET
    @Path("/disk-usage")
    @Produces(MediaType.APPLICATION_JSON)
    public DiskUsageView diskUsage() {
        try {
            java.nio.file.Path directory = java.nio.file.Path.of(downloadDirectory);
            Files.createDirectories(directory);
            long freeBytes = Files.getFileStore(directory).getUsableSpace();
            return new DiskUsageView(freeBytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
