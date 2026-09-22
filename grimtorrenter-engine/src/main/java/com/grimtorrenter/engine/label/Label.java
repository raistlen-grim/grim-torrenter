package com.grimtorrenter.engine.label;

/** A label's stable, never-displayed id and its mutable display name. Torrents store the id, so
 * renaming a label never touches a torrent. See design_docs/0077. */
public record Label(String id, String name) {
}
