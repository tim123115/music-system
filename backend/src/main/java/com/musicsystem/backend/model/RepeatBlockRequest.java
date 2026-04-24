package com.musicsystem.backend.model;

import java.util.List;

/**
 * Future repeat/ending block definition.
 * Example: label=A, passes=[A, A'], repeats=2.
 */
public record RepeatBlockRequest(
        String label,
        List<String> passes,
        Integer repeats
) {
}
