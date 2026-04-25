package com.musicsystem.backend.model;

import java.util.List;

public record ArrangementRequest(
        String songName,
        String timeSignature,
        Integer tempo,
        String style,
        String soundfontPath,
        String keySignature,
        Integer transposeSemitones,
        String arrangementMode,
        String repeatMarkers,
        Integer songRepeats,
        List<String> measures,
        List<SectionRequest> sections,
        RepeatPlanRequest repeatPlan
) {
}
