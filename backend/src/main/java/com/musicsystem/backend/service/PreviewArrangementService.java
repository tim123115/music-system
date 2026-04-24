package com.musicsystem.backend.service;

import com.musicsystem.backend.model.ArrangementRequest;
import com.musicsystem.backend.model.ArrangementResponse;
import com.musicsystem.backend.model.ChartBar;
import com.musicsystem.backend.model.PreviewNote;
import com.musicsystem.backend.model.SectionRequest;
import org.springframework.stereotype.Service;

import javax.sound.midi.*;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PreviewArrangementService implements ArrangementService {

    private static final Map<String, Integer> NOTE_TO_SEMITONE = Map.ofEntries(
            Map.entry("C", 0), Map.entry("C#", 1), Map.entry("DB", 1),
            Map.entry("D", 2), Map.entry("D#", 3), Map.entry("EB", 3),
            Map.entry("E", 4), Map.entry("F", 5), Map.entry("F#", 6),
            Map.entry("GB", 6), Map.entry("G", 7), Map.entry("G#", 8),
            Map.entry("AB", 8), Map.entry("A", 9), Map.entry("A#", 10),
            Map.entry("BB", 10), Map.entry("B", 11)
    );
    private static final List<String> SUPPORTED_STYLES = List.of("PopBallad", "BossaNova", "Swing4", "FunkRock", "Shuffle");

    @Override
    public ArrangementResponse buildPreview(ArrangementRequest request) {
        int beatsPerBar = parseBeatsPerBar(request.timeSignature());
        int tempo = request.tempo() == null || request.tempo() <= 0 ? 100 : request.tempo();
        int songRepeats = request.songRepeats() == null || request.songRepeats() < 1 ? 1 : request.songRepeats();
        String style = normalizeStyle(request.style());
        List<String> warnings = new ArrayList<>();
        if (request.repeatPlan() != null) {
            warnings.add("repeatPlan received but not yet implemented in preview engine; using legacy section sequence.");
        }
        List<PreviewNote> notes = new ArrayList<>();
        List<ChartBar> chartBars = buildSinglePassChartBars(request);
        StylePattern stylePattern = stylePattern(style);

        for (int repeat = 0; repeat < songRepeats; repeat++) {
            for (int bar = 0; bar < chartBars.size(); bar++) {
                ChartBar chartBar = chartBars.get(bar);
                List<String> beatChords = parseBeatChords(chartBar.source(), beatsPerBar, warnings, bar + 1);
                for (int beat = 0; beat < beatChords.size(); beat++) {
                    String chord = beatChords.get(beat);
                    int root = parseChordRoot(chord, warnings, bar + 1);
                    int[] chordTones = chordTones(root, chord);
                    double barStart = (repeat * chartBars.size() + bar) * beatsPerBar;
                    for (PatternHit hit : stylePattern.hits()) {
                        double beatOffset = barStart + beat + hit.offsetInBeat();
                        notes.add(new PreviewNote(beatOffset, hit.durationBeat(), chordTones[0] - 12, hit.bassVelocity()));
                        notes.add(new PreviewNote(beatOffset, hit.durationBeat(), chordTones[0], hit.chordVelocity()));
                        notes.add(new PreviewNote(beatOffset, hit.durationBeat(), chordTones[1], hit.chordVelocity() - 6));
                        notes.add(new PreviewNote(beatOffset, hit.durationBeat(), chordTones[2], hit.chordVelocity() - 10));
                    }
                }
            }
        }

        String midiBase64 = encodeMidi(notes, tempo);
        return new ArrangementResponse(
                safe(request.songName(), "Untitled"),
                tempo,
                style,
                notes,
                chartBars,
                midiBase64,
                warnings
        );
    }

    public List<String> supportedStyles() {
        return SUPPORTED_STYLES;
    }

    private List<ChartBar> buildSinglePassChartBars(ArrangementRequest request) {
        List<ChartBar> chartBars = new ArrayList<>();
        int songRepeats = request.songRepeats() == null || request.songRepeats() < 1 ? 1 : request.songRepeats();
        if (request.sections() != null && !request.sections().isEmpty()) {
            var sectionMap = new java.util.LinkedHashMap<String, SectionRequest>();
            for (SectionRequest section : request.sections()) {
                sectionMap.put(safe(section.name(), "Section"), section);
            }
            List<String> markerOrder = resolveMarkerOrder(request.repeatMarkers(), sectionMap);
            for (int repeat = 0; repeat < songRepeats; repeat++) {
                for (String marker : markerOrder) {
                    SectionRequest section = sectionMap.get(marker);
                    List<String> measures = section.measures() == null ? List.of() : section.measures();
                    for (int i = 0; i < measures.size(); i++) {
                        chartBars.add(new ChartBar(marker, repeat + 1, i + 1, safe(measures.get(i), "C")));
                    }
                }
            }
            return chartBars;
        }
        List<String> measures = request.measures() == null ? List.of() : request.measures();
        for (int repeat = 0; repeat < songRepeats; repeat++) {
            for (int i = 0; i < measures.size(); i++) {
                chartBars.add(new ChartBar("Main", repeat + 1, i + 1, safe(measures.get(i), "C")));
            }
        }
        return chartBars;
    }

    private List<String> resolveMarkerOrder(String repeatMarkers, java.util.LinkedHashMap<String, SectionRequest> sectionMap) {
        if (repeatMarkers == null || repeatMarkers.isBlank()) {
            return new ArrayList<>(sectionMap.keySet());
        }
        String markers = repeatMarkers.trim();
        List<String> tokens;
        if (markers.matches("^[A-Za-z']+$") && sectionMap.keySet().stream().allMatch(k -> k.matches("^[A-Za-z']+$"))) {
            tokens = markers.chars().mapToObj(c -> String.valueOf((char) c)).toList();
        } else {
            tokens = java.util.Arrays.stream(markers.split("[\\s,;|]+")).filter(s -> !s.isBlank()).toList();
        }
        List<String> ordered = new ArrayList<>();
        for (String token : tokens) {
            if (sectionMap.containsKey(token)) {
                ordered.add(token);
            }
        }
        return ordered.isEmpty() ? new ArrayList<>(sectionMap.keySet()) : ordered;
    }

    private List<String> parseBeatChords(String measureText, int beatsPerBar, List<String> warnings, int barNo) {
        String[] tokens = splitChords(measureText);
        if (tokens.length == 2 && !hasSlashMarker(tokens)) {
            int firstHalfBeats = Math.max(1, beatsPerBar / 2);
            int secondHalfBeats = Math.max(1, beatsPerBar - firstHalfBeats);
            List<String> beats = new ArrayList<>();
            for (int i = 0; i < firstHalfBeats; i++) {
                beats.add(tokens[0]);
            }
            for (int i = 0; i < secondHalfBeats; i++) {
                beats.add(tokens[1]);
            }
            return beats;
        }
        List<String> beats = new ArrayList<>();
        String lastChord = "C";
        for (String token : tokens) {
            if (token.equals("/")) {
                beats.add(lastChord);
                continue;
            }
            if (token.startsWith("/")) {
                int slashCount = countLeadingSlashes(token);
                for (int i = 0; i < slashCount; i++) {
                    beats.add(lastChord);
                }
                String chordPart = token.substring(slashCount);
                if (!chordPart.isBlank()) {
                    lastChord = chordPart;
                    beats.add(lastChord);
                }
                continue;
            }
            lastChord = token;
            beats.add(lastChord);
        }
        if (beats.size() > beatsPerBar) {
            warnings.add("Bar " + barNo + " exceeds " + beatsPerBar + " beats; extra entries ignored.");
            return beats.subList(0, beatsPerBar);
        }
        while (beats.size() < beatsPerBar) {
            beats.add(lastChord);
        }
        return beats;
    }

    private boolean hasSlashMarker(String[] tokens) {
        for (String token : tokens) {
            if (token.equals("/") || token.startsWith("/")) {
                return true;
            }
        }
        return false;
    }

    private int countLeadingSlashes(String token) {
        int count = 0;
        while (count < token.length() && token.charAt(count) == '/') {
            count++;
        }
        return count;
    }

    private String[] splitChords(String measureText) {
        if (measureText == null || measureText.isBlank()) {
            return new String[]{"C"};
        }
        String normalized = measureText
                .replace("|", " ")
                .replace(",", " ")
                .replace(";", " ")
                .trim();
        return normalized.isEmpty() ? new String[]{"C"} : normalized.split("\\s+");
    }

    private int parseChordRoot(String chord, List<String> warnings, int barNo) {
        String normalized = safe(chord, "C").toUpperCase(Locale.ROOT).trim();
        String root = normalized.length() > 1 && (normalized.charAt(1) == '#' || normalized.charAt(1) == 'B')
                ? normalized.substring(0, 2)
                : normalized.substring(0, 1);
        Integer semitone = NOTE_TO_SEMITONE.get(root);
        if (semitone == null) {
            warnings.add("Unknown chord '" + chord + "' at bar " + barNo + ", fallback to C.");
            semitone = 0;
        }
        return 60 + semitone;
    }

    private int[] chordTones(int root, String chordSymbol) {
        String normalized = safe(chordSymbol, "").toLowerCase(Locale.ROOT);
        if (normalized.contains("dim")) {
            return new int[]{root, root + 3, root + 6};
        }
        if (normalized.contains("sus")) {
            return new int[]{root, root + 5, root + 7};
        }
        if (normalized.contains("m") && !normalized.contains("maj")) {
            return new int[]{root, root + 3, root + 7};
        }
        return new int[]{root, root + 4, root + 7};
    }

    private StylePattern stylePattern(String style) {
        return switch (style) {
            case "BossaNova" -> new StylePattern(List.of(
                    new PatternHit(0.0, 0.55, 86, 70),
                    new PatternHit(0.5, 0.35, 74, 62)
            ));
            case "Swing4" -> new StylePattern(List.of(
                    new PatternHit(0.0, 0.66, 90, 72),
                    new PatternHit(2.0 / 3.0, 0.34, 78, 64)
            ));
            case "FunkRock" -> new StylePattern(List.of(
                    new PatternHit(0.0, 0.30, 92, 74),
                    new PatternHit(0.5, 0.25, 82, 66),
                    new PatternHit(0.75, 0.20, 76, 60)
            ));
            case "Shuffle" -> new StylePattern(List.of(
                    new PatternHit(0.0, 0.50, 88, 70),
                    new PatternHit(2.0 / 3.0, 0.34, 76, 62)
            ));
            default -> new StylePattern(List.of(
                    new PatternHit(0.0, 0.65, 92, 74),
                    new PatternHit(0.5, 0.30, 80, 66)
            ));
        };
    }

    private int parseBeatsPerBar(String timeSignature) {
        if (timeSignature == null || !timeSignature.contains("/")) {
            return 4;
        }
        try {
            return Integer.parseInt(timeSignature.split("/")[0]);
        } catch (NumberFormatException ignored) {
            return 4;
        }
    }

    private String encodeMidi(List<PreviewNote> notes, int bpm) {
        try {
            Sequence sequence = new Sequence(Sequence.PPQ, 480);
            Track track = sequence.createTrack();
            addTempoEvent(track, bpm);

            for (PreviewNote note : notes) {
                long startTick = Math.round(note.startBeat() * 480);
                long endTick = Math.round((note.startBeat() + note.durationBeat()) * 480);
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON, 0, note.midi(), note.velocity()), startTick));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF, 0, note.midi(), 0), endTick));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MidiSystem.write(sequence, 1, out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private void addTempoEvent(Track track, int bpm) throws InvalidMidiDataException {
        int mpq = 60_000_000 / Math.max(1, bpm);
        byte[] data = new byte[]{
                (byte) (mpq >> 16),
                (byte) (mpq >> 8),
                (byte) mpq
        };
        MetaMessage meta = new MetaMessage(0x51, data, 3);
        track.add(new MidiEvent(meta, 0));
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeStyle(String style) {
        if (style == null || style.isBlank()) {
            return SUPPORTED_STYLES.getFirst();
        }
        return SUPPORTED_STYLES.stream()
                .filter(s -> s.equalsIgnoreCase(style))
                .findFirst()
                .orElse(SUPPORTED_STYLES.getFirst());
    }

    private record PatternHit(double offsetInBeat, double durationBeat, int bassVelocity, int chordVelocity) {
    }

    private record StylePattern(List<PatternHit> hits) {
    }
}
