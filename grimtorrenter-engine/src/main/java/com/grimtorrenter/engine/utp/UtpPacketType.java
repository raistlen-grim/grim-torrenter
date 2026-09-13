package com.grimtorrenter.engine.utp;

/** BEP 29's five packet types - the high nibble of the wire header's first byte. See
 * design_docs/0074. */
public enum UtpPacketType {
    DATA(0),
    FIN(1),
    STATE(2),
    RESET(3),
    SYN(4);

    private final int value;

    UtpPacketType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static UtpPacketType fromValue(int value) {
        for (UtpPacketType type : values()) {
            if (type.value == value) {
                return type;
            }
        }
        throw new UtpException("Unknown uTP packet type: " + value);
    }
}
