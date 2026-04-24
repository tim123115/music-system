package com.musicsystem.backend.model;

import java.util.List;

public record SectionRequest(
        String name,
        List<String> measures
) {
}
