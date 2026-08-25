package com.grimtorrenter.engine.bencode;

import java.util.List;

public record BList(List<BValue> values) implements BValue {

    public BList {
        values = List.copyOf(values);
    }
}
