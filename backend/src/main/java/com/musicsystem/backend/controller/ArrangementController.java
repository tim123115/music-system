package com.musicsystem.backend.controller;

import com.musicsystem.backend.model.ArrangementRequest;
import com.musicsystem.backend.model.ArrangementResponse;
import com.musicsystem.backend.model.AudioRenderResponse;
import com.musicsystem.backend.service.ArrangementService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/arrangements")
@CrossOrigin(origins = "*")
public class ArrangementController {
    private final ArrangementService arrangementService;

    public ArrangementController(ArrangementService arrangementService) {
        this.arrangementService = arrangementService;
    }

    @PostMapping("/preview")
    public ArrangementResponse preview(@RequestBody ArrangementRequest request) {
        validateMeasures(request);
        return arrangementService.buildPreview(request);
    }

    @PostMapping("/render-audio")
    public AudioRenderResponse renderAudio(@RequestBody ArrangementRequest request) {
        validateMeasures(request);
        try {
            return arrangementService.renderAudio(request);
        } catch (UnsupportedOperationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/rhythms")
    public List<String> rhythms(@RequestParam(required = false) String timeSignature) {
        return arrangementService.supportedStyles(timeSignature);
    }

    @GetMapping("/soundfonts")
    public List<String> soundfonts() {
        return arrangementService.availableSoundfonts();
    }

    private void validateMeasures(ArrangementRequest request) {
        boolean hasFlatMeasures = request != null && request.measures() != null && !request.measures().isEmpty();
        boolean hasSections = request != null && request.sections() != null && !request.sections().isEmpty();
        if (!hasFlatMeasures && !hasSections) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one measure is required.");
        }
    }
}
