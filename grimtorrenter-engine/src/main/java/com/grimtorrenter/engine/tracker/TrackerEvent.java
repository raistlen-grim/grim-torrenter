package com.grimtorrenter.engine.tracker;

public enum TrackerEvent {
    STARTED("started"),
    STOPPED("stopped"),
    COMPLETED("completed");

    private final String wireName;

    TrackerEvent(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
