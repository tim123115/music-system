package com.musicsystem.backend.model;

import java.util.List;

public record AudioRenderResponse(
        String songName,
        int tempo,
        String style,
        String wavBase64,
        List<String> warnings
) {
}
