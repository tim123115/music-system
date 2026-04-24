package com.musicsystem.backend.service;

import com.musicsystem.backend.model.ArrangementRequest;
import com.musicsystem.backend.model.ArrangementResponse;
import com.musicsystem.backend.model.AudioRenderResponse;

import java.util.List;

public interface ArrangementService {
    ArrangementResponse buildPreview(ArrangementRequest request);

    default AudioRenderResponse renderAudio(ArrangementRequest request) {
        throw new UnsupportedOperationException("Audio rendering is not supported by current engine.");
    }

    default List<String> supportedStyles() {
        return List.of();
    }

    default List<String> supportedStyles(String timeSignature) {
        return supportedStyles();
    }
}
