package com.grimtorrenter.app;

import com.grimtorrenter.engine.engine.TorrentEngine;
import com.grimtorrenter.engine.label.Label;
import com.grimtorrenter.engine.label.LabelConflictException;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;

/** The managed label list (design_docs/0077). No DTO wrapper - Label is just an id and a name.
 * Delete goes through TorrentEngine (not the registry directly) so the id is also stripped from
 * every torrent carrying it. */
@Path("/api/labels")
public class LabelResource {

    /** Request body for create and rename. */
    public record LabelRequest(String name) {
    }

    @Inject
    TorrentEngine torrentEngine;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Label> list() {
        return torrentEngine.labels().list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Label create(LabelRequest request) {
        try {
            return torrentEngine.labels().create(request == null ? null : request.name());
        } catch (LabelConflictException e) {
            throw conflict(e);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        } catch (UncheckedIOException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Label rename(@PathParam("id") String id, LabelRequest request) {
        try {
            return torrentEngine.labels().rename(id, request == null ? null : request.name());
        } catch (NoSuchElementException e) {
            throw new NotFoundException(e.getMessage());
        } catch (LabelConflictException e) {
            throw conflict(e);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(e.getMessage());
        } catch (UncheckedIOException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @DELETE
    @Path("/{id}")
    public void delete(@PathParam("id") String id) {
        if (!torrentEngine.deleteLabel(id)) {
            throw new NotFoundException("No such label: " + id);
        }
    }

    private static WebApplicationException conflict(LabelConflictException e) {
        return new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
    }
}
