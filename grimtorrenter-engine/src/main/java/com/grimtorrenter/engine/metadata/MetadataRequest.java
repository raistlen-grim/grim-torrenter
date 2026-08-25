package com.grimtorrenter.engine.metadata;

/** msg_type 0 - "please send me this metadata piece." */
public record MetadataRequest(int piece) implements UtMetadataMessage {
}
