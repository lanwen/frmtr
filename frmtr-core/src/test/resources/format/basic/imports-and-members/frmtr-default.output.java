package dev.example;

import static java.util.Collections.emptyList;

import java.util.List;

public class PreferenceSnapshot {

    private final int value = 1;

    public PreferenceSnapshot(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }
}
