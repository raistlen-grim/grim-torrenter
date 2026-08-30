package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.sun.management.OperatingSystemMXBean;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.util.List;

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

    @Inject
    TorrentEngine torrentEngine;

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

    /** {@code com.sun.management.OperatingSystemMXBean} rather than the plain
     * {@code java.lang.management} one - the plain interface has no per-process CPU figure at
     * all, only a system load average. No extra dependency: {@code com.sun.management} ships in
     * every mainstream JDK, just isn't part of the Java SE platform API. Both
     * {@code availableProcessors()} and the CPU load figures are already container-quota-aware
     * on modern JDKs (active by default since JDK 10, further refined for cgroup v2 in later
     * releases), so this reports what the container actually gets, not the host's full core
     * count - relevant since this app ships as a single Docker container per the top-level
     * CLAUDE.md. */
    @GET
    @Path("/resource-usage")
    @Produces(MediaType.APPLICATION_JSON)
    public ResourceUsageView resourceUsage() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        var heap = memory.getHeapMemoryUsage();
        var os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        return new ResourceUsageView(heap.getUsed(), heap.getMax(), os.getProcessCpuLoad(),
                os.getAvailableProcessors());
    }

    /** Engine-wide singleton subsystems only (DHT, the inbound peer server) - per-torrent
     * status stays on the torrent itself. See design_docs/0059. */
    @GET
    @Path("/services")
    @Produces(MediaType.APPLICATION_JSON)
    public List<ServiceStatusView> services() {
        return torrentEngine.serviceStatuses().stream().map(ServiceStatusView::from).toList();
    }
}
