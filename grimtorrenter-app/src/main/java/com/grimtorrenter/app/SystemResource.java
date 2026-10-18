package com.grimtorrenter.app;

import com.grimtorrenter.engine.ClientIdentity;
import com.grimtorrenter.engine.engine.TorrentEngine;
import com.sun.management.OperatingSystemMXBean;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;

/** Global, torrent-independent host state - same "separate small resource" shape as
 * DhtResource, since this isn't scoped to any one torrent either. See design_docs/0043. */
@Path("/api/system")
public class SystemResource {

    @Inject
    AppDirectories directories;

    @Inject
    TorrentEngine torrentEngine;

    @Inject
    HealthService healthService;

    /** Free space on the downloads volume - the same figure as the Health page's freeSpace
     * check (AppDirectories.downloadsUsableSpace()). */
    @GET
    @Path("/disk-usage")
    @Produces(MediaType.APPLICATION_JSON)
    public DiskUsageView diskUsage() {
        try {
            return new DiskUsageView(directories.downloadsUsableSpace());
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

    /** Which build this is - shown in the UI footer so a bug report can name it. The full
     * version, qualifier included; see ClientIdentity and design_docs/0084. */
    @GET
    @Path("/version")
    @Produces(MediaType.APPLICATION_JSON)
    public VersionView version() {
        return new VersionView(ClientIdentity.NAME, ClientIdentity.version());
    }

    public record VersionView(String name, String version) {
    }

    /** The full health report behind the Health page - see HealthView and design_docs/0086. */
    @GET
    @Path("/health")
    @Produces(MediaType.APPLICATION_JSON)
    public HealthView health() {
        return healthService.report();
    }

    public record LivenessView(String status) {
    }

    /** For the container's HEALTHCHECK: 200 while the app can do its job, 503 when it cannot
     * (see HealthService.isLive()). Says nothing but UP/DOWN, and is reachable without a login
     * (AuthenticationFilter) - a health probe has no way to hold a session. */
    @GET
    @Path("/healthz")
    @Produces(MediaType.APPLICATION_JSON)
    public Response healthz() {
        return healthService.isLive()
                ? Response.ok(new LivenessView("UP")).build()
                : Response.status(Response.Status.SERVICE_UNAVAILABLE).entity(new LivenessView("DOWN")).build();
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
