package com.musicsystem.backend.model;

import java.util.List;

/**
 * Optional advanced repeat plan for first/second ending semantics.
 * Backward compatible: if null, backend uses legacy sequential section behavior.
 */
public record RepeatPlanRequest(
        List<RepeatBlockRequest> blocks
) {
}
