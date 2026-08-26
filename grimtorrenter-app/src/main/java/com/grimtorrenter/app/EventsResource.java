package com.grimtorrenter.app;

import com.grimtorrenter.engine.events.EventStore;
import com.grimtorrenter.engine.events.LibraryEvent;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** Read access to the library event feed (design_docs/0055) for the frontend's Events page -
 * scrollback on load, with live updates arriving separately over the WebSocket "event" message.
 * Response size is naturally bounded by Settings.eventLogRetentionDays, the same window that
 * bounds on-disk size, so no separate pagination scheme is needed for a first cut. No DTO
 * wrapper - LibraryEvent has no engine internals to hide, same reasoning Settings itself was
 * given in design_docs/0045. */
@Path("/api/events")
public class EventsResource {

    @Inject
    EventStore eventStore;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<LibraryEvent> list(@QueryParam("infoHash") String infoHash) {
        return infoHash != null ? eventStore.forTorrent(infoHash) : eventStore.all();
    }
}
