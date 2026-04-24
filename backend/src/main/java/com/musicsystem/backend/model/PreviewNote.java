package com.musicsystem.backend.model;

public record PreviewNote(
        double startBeat,
        double durationBeat,
        int midi,
        int velocity
) {
}
