package com.musicsystem.backend.model;

import java.util.List;

public record ArrangementResponse(
        String songName,
        int tempo,
        String style,
        List<PreviewNote> previewNotes,
        List<ChartBar> chartBars,
        String midiBase64,
        List<String> warnings
) {
}
