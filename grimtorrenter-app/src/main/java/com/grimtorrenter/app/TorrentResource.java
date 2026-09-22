package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.magnet.MagnetLink;
import com.grimtorrenter.engine.metainfo.InfoHash;
import com.grimtorrenter.engine.piece.FilePriorities;
import com.grimtorrenter.engine.piece.FilePriority;
import com.grimtorrenter.engine.torrent.SeedingLimitOverride;
import com.grimtorrenter.engine.torrent.TorrentLimitOverride;
import com.grimtorrenter.engine.torrent.TorrentSession;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.stream.Stream;

@Path("/api/torrents")
public class TorrentResource {

    @Inject
    TorrentEngine torrentEngine;

    /** Pending magnets (design_docs/0070) ride the same list as resolved torrents - a magnet
     * mid-fetch and a real torrent are mutually exclusive per info hash (TorrentEngine's own
     * concludePendingMagnet() guarantees it), so there's nothing to de-duplicate here. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<TorrentView> list() {
        return Stream.concat(
                        torrentEngine.listTorrents().stream().map(TorrentView::from),
                        torrentEngine.listPendingMagnets().stream().map(TorrentView::fromPendingMagnet))
                .toList();
    }

    @GET
    @Path("/{infoHash}")
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentView get(@PathParam("infoHash") String infoHashHex) {
        InfoHash infoHash = parseInfoHash(infoHashHex);
        return torrentEngine.getTorrent(infoHash)
                .map(TorrentView::from)
                .or(() -> torrentEngine.getPendingMagnet(infoHash).map(TorrentView::fromPendingMagnet))
                .orElseThrow(() -> new NotFoundException("Torrent not found: " + infoHashHex));
    }

    /** Per-piece state (NEEDED/IN_PROGRESS/COMPLETE), in index order, plus pieceLength - see
     * design_docs/0032/0031. Self-contained detail endpoint, not part of the
     * always-broadcast TorrentView. */
    @GET
    @Path("/{infoHash}/pieces")
    @Produces(MediaType.APPLICATION_JSON)
    public PiecesView pieces(@PathParam("infoHash") String infoHashHex) {
        return PiecesView.from(requireSession(infoHashHex));
    }

    /** Self-contained detail endpoint - path, total length, and per-file download
     * progress. See design_docs/0031. */
    @GET
    @Path("/{infoHash}/files")
    @Produces(MediaType.APPLICATION_JSON)
    public List<FileView> files(@PathParam("infoHash") String infoHashHex) {
        return requireSession(infoHashHex).files().stream().map(FileView::from).toList();
    }

    /** Body is one priority name (SKIP/LOW/MEDIUM/HIGH) per file, in the same order GET
     * .../files returns them - whole-array rather than per-file so a client can express "skip
     * everything except X" atomically. Responds with the updated file list so the caller never
     * has to reconcile anything itself. A wrong-length array, an unknown name, or every file
     * skipped is a 400. See design_docs/0075. */
    @PUT
    @Path("/{infoHash}/files/priorities")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<FileView> updateFilePriorities(@PathParam("infoHash") String infoHashHex,
                                               List<String> priorityNames) {
        InfoHash infoHash = parseInfoHash(infoHashHex);
        if (priorityNames == null) {
            throw new BadRequestException("Expected a JSON array of priority names");
        }
        FilePriorities priorities;
        try {
            priorities = new FilePriorities(priorityNames.stream().map(FilePriority::valueOf).toList());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BadRequestException("Unknown file priority - expected SKIP, LOW, MEDIUM or HIGH");
        }
        boolean found;
        try {
            found = torrentEngine.setFilePriorities(infoHash, priorities);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        if (!found) {
            throw new NotFoundException("Torrent not found: " + infoHashHex);
        }
        return files(infoHashHex);
    }

    /** See design_docs/0031 - transfer rate, % piece availability, and client-name
     * decoding aren't included yet, only what PeerConnection already tracks. */
    @GET
    @Path("/{infoHash}/peers")
    @Produces(MediaType.APPLICATION_JSON)
    public List<PeerView> peers(@PathParam("infoHash") String infoHashHex) {
        return requireSession(infoHashHex).peers().stream().map(PeerView::from).toList();
    }

    /** Self-contained detail endpoint - per-tracker status (URL, tier, WORKING/ERROR/
     * UNKNOWN, last/next announce, last error, seeders/leechers). Empty for a trackerless
     * torrent. See design_docs/0031. */
    @GET
    @Path("/{infoHash}/trackers")
    @Produces(MediaType.APPLICATION_JSON)
    public List<TrackerView> trackers(@PathParam("infoHash") String infoHashHex) {
        return requireSession(infoHashHex).trackers().stream().map(TrackerView::from).toList();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    public AddTorrentResponse add(@BeanParam TorrentUploadForm form) throws IOException {
        if (form.file == null) {
            throw new BadRequestException("Missing 'file' form field");
        }
        byte[] bytes = Files.readAllBytes(form.file.uploadedFile());
        TorrentEngine.AddTorrentResult result = torrentEngine.addTorrent(bytes);
        return new AddTorrentResponse(TorrentView.from(result.session()), result.alreadyExisted());
    }

    /** Returns synchronously, unlike the metadata fetch itself (still genuinely async, on its
     * own background thread) - registering the magnet as pending is fast local disk I/O, so
     * there's always a real resource to hand back immediately: either the pending entry
     * (state "FETCHING_METADATA") or, if this info hash already resolved before, the existing
     * torrent. See design_docs/0070 (supersedes design_docs/0028's own "nothing to return yet"
     * note). */
    @POST
    @Path("/magnet")
    @Consumes(MediaType.TEXT_PLAIN)
    @Produces(MediaType.APPLICATION_JSON)
    public AddTorrentResponse addMagnet(String magnetUri) {
        TorrentEngine.AddMagnetResult result = torrentEngine.addMagnet(MagnetLink.parse(magnetUri));
        TorrentView torrent = result.existingSession() != null
                ? TorrentView.from(result.existingSession())
                : TorrentView.fromPendingMagnet(result.pending());
        return new AddTorrentResponse(torrent, result.alreadyExisted());
    }

    @DELETE
    @Path("/{infoHash}")
    public void remove(@PathParam("infoHash") String infoHashHex,
                        @QueryParam("deleteData") @DefaultValue("false") boolean deleteData) {
        torrentEngine.removeTorrent(parseInfoHash(infoHashHex), deleteData);
    }

    @POST
    @Path("/{infoHash}/pause")
    public void pause(@PathParam("infoHash") String infoHashHex) {
        torrentEngine.pauseTorrent(parseInfoHash(infoHashHex));
    }

    @POST
    @Path("/{infoHash}/resume")
    public void resume(@PathParam("infoHash") String infoHashHex) {
        torrentEngine.resumeTorrent(parseInfoHash(infoHashHex));
    }

    /** No DTO wrapper - SeedingLimitOverride has no engine internals to hide, same reasoning
     * Settings itself was given in design_docs/0045. See design_docs/0054. */
    @GET
    @Path("/{infoHash}/seeding-limits")
    @Produces(MediaType.APPLICATION_JSON)
    public SeedingLimitOverride seedingLimits(@PathParam("infoHash") String infoHashHex) {
        return requireSession(infoHashHex).seedingLimitOverride();
    }

    @PUT
    @Path("/{infoHash}/seeding-limits")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public SeedingLimitOverride updateSeedingLimits(@PathParam("infoHash") String infoHashHex,
                                                     SeedingLimitOverride override) {
        InfoHash infoHash = parseInfoHash(infoHashHex);
        torrentEngine.setSeedingLimitOverride(infoHash, override);
        return requireSession(infoHashHex).seedingLimitOverride();
    }

    /** No DTO wrapper - TorrentLimitOverride has no engine internals to hide, same reasoning as
     * seedingLimits() above. See design_docs/0072. */
    @GET
    @Path("/{infoHash}/limits")
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentLimitOverride limits(@PathParam("infoHash") String infoHashHex) {
        return requireSession(infoHashHex).torrentLimits();
    }

    @PUT
    @Path("/{infoHash}/limits")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentLimitOverride updateLimits(@PathParam("infoHash") String infoHashHex,
                                              TorrentLimitOverride override) {
        InfoHash infoHash = parseInfoHash(infoHashHex);
        torrentEngine.setTorrentLimits(infoHash, override);
        return requireSession(infoHashHex).torrentLimits();
    }

    /** Body is the torrent's complete list of label ids (ids from GET /api/labels, not names);
     * responds with the updated torrent. An unknown id, or more labels than the per-torrent cap,
     * is a 400. See design_docs/0077. */
    @PUT
    @Path("/{infoHash}/labels")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public TorrentView updateLabels(@PathParam("infoHash") String infoHashHex, List<String> labelIds) {
        InfoHash infoHash = parseInfoHash(infoHashHex);
        if (labelIds == null || labelIds.contains(null)) {
            throw new BadRequestException("Expected a JSON array of label ids");
        }
        boolean found;
        try {
            found = torrentEngine.setTorrentLabels(infoHash, labelIds);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        }
        if (!found) {
            throw new NotFoundException("Torrent not found: " + infoHashHex);
        }
        return TorrentView.from(requireSession(infoHashHex));
    }

    private TorrentSession requireSession(String infoHashHex) {
        return torrentEngine.getTorrent(parseInfoHash(infoHashHex))
                .orElseThrow(() -> new NotFoundException("Torrent not found: " + infoHashHex));
    }

    private InfoHash parseInfoHash(String hex) {
        try {
            return new InfoHash(hex);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Invalid info hash: " + hex);
        }
    }
}
