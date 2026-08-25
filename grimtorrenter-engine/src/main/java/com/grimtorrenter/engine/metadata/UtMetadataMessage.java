package com.grimtorrenter.engine.metadata;

/**
 * BEP 9's ut_metadata message family - the bencoded structure carried
 * inside a BEP 10 {@code Extended} message's payload once "ut_metadata"
 * has been negotiated between two peers. See design_docs/0028.
 */
public sealed interface UtMetadataMessage permits MetadataRequest, MetadataData, MetadataReject {

    int piece();
}
