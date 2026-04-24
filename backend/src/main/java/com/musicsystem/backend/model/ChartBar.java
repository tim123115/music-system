package com.musicsystem.backend.model;

public record ChartBar(
        String section,
        int repeatIndex,
        int barIndexInSection,
        String source
) {
}
