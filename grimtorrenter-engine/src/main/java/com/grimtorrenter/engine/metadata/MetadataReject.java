package com.grimtorrenter.engine.metadata;

/** msg_type 2 - "I don't have (or won't send you) this metadata piece." */
public record MetadataReject(int piece) implements UtMetadataMessage {
}
