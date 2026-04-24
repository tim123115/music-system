package com.musicsystem.backend.service;

import com.musicsystem.backend.model.ArrangementRequest;
import com.musicsystem.backend.model.ArrangementResponse;
import com.musicsystem.backend.model.AudioRenderResponse;
import com.musicsystem.backend.model.ChartBar;
import com.musicsystem.backend.model.SectionRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.jjazz.embeddedsynth.api.EmbeddedSynth;
import org.jjazz.embeddedsynth.api.EmbeddedSynthException;
import org.jjazz.embeddedsynth.spi.EmbeddedSynthProvider;
import org.jjazz.chordleadsheet.api.UnsupportedEditException;
import org.jjazz.chordleadsheet.spi.item.CLI_Factory;
import org.jjazz.harmony.api.TimeSignature;
import org.jjazz.midimix.api.MidiMix;
import org.jjazz.midimix.spi.MidiMixManager;
import org.jjazz.musiccontrol.api.SongMidiExporter;
import org.jjazz.outputsynth.spi.OutputSynthManager;
import org.jjazz.rhythm.api.Rhythm;
import org.jjazz.rhythm.api.RP_State;
import org.jjazz.rhythmdatabase.api.RhythmDatabase;
import org.jjazz.rhythmdatabase.api.RhythmInfo;
import org.jjazz.rhythmdatabase.api.UnavailableRhythmException;
import org.jjazz.rhythmparametersimpl.api.RP_SYS_Fill;
import org.jjazz.rhythmparametersimpl.api.RP_SYS_Variation;
import org.jjazz.song.api.Song;
import org.jjazz.song.spi.SongFactory;
import org.jjazz.songstructure.api.SongPart;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Integration placeholder for JJazzLab Toolkit.
 *
 * Keep this class separate so you can switch from PreviewArrangementService
 * to real JJazz generation without changing controller contracts.
 */
@Service
@Primary
@ConditionalOnProperty(name = "arrangement.engine", havingValue = "jjazz")
public class JJazzToolkitArrangementService implements ArrangementService {
    private static final List<String> TOOLKIT_STYLE_HINTS = List.of(
            "Cool8Beat.S737.sst-ID",
            "BossaNova2.S469.prs-ID",
            "MediumJazz.S737.sst-ID",
            "StandardRock.STY-ID"
    );

    private final ObjectProvider<PreviewArrangementService> previewArrangementServiceProvider;
    private final String soundFontPath;
    private final String toolkitRepoPath;
    private final String coreRepoPath;

    public JJazzToolkitArrangementService(
            ObjectProvider<PreviewArrangementService> previewArrangementServiceProvider,
            @Value("${jjazz.soundfont.path:}") String soundFontPath,
            @Value("${jjazz.toolkit.repo.path:}") String toolkitRepoPath,
            @Value("${jjazz.core.repo.path:}") String coreRepoPath
    ) {
        this.previewArrangementServiceProvider = previewArrangementServiceProvider;
        this.soundFontPath = soundFontPath == null ? "" : soundFontPath.trim();
        this.toolkitRepoPath = toolkitRepoPath == null ? "" : toolkitRepoPath.trim();
        this.coreRepoPath = coreRepoPath == null ? "" : coreRepoPath.trim();
    }

    @Override
    public ArrangementResponse buildPreview(ArrangementRequest request) {
        List<String> warnings = new ArrayList<>();
        try {
            return buildWithToolkit(request, warnings);
        } catch (Exception ex) {
            warnings.add("JJazz generation failed, fallback to preview engine: " + ex.getMessage());
            return fallbackToPreview(request, warnings);
        }
    }

    @Override
    public List<String> supportedStyles() {
        return supportedStyles(null);
    }

    @Override
    public List<String> supportedStyles(String timeSignature) {
        try {
            RhythmDatabase rdb = RhythmDatabase.getSharedInstance();
            TimeSignature ts = null;
            if (timeSignature != null && !timeSignature.isBlank()) {
                try {
                    ts = TimeSignature.parse(timeSignature.trim());
                } catch (ParseException ignored) {
                    // Keep null and return all rhythms.
                }
            }
            final TimeSignature finalTs = ts;
            return rdb.getRhythms().stream()
                    .filter(ri -> finalTs == null || ri.timeSignature().equals(finalTs))
                    .map(RhythmInfo::name)
                    .distinct()
                    .sorted()
                    .limit(400)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            return TOOLKIT_STYLE_HINTS;
        }
    }

    @Override
    public AudioRenderResponse renderAudio(ArrangementRequest request) {
        ArrangementResponse preview = buildPreview(request);
        List<String> warnings = new ArrayList<>(preview.warnings());
        if (preview.midiBase64() == null || preview.midiBase64().isBlank()) {
            throw new UnsupportedOperationException("No MIDI generated, unable to render audio.");
        }

        File midiFile = null;
        File wavFile = null;
        try {
            byte[] midiBytes = Base64.getDecoder().decode(preview.midiBase64());
            midiFile = Files.createTempFile("jjazz-render-", ".mid").toFile();
            wavFile = Files.createTempFile("jjazz-render-", ".wav").toFile();
            Files.write(midiFile.toPath(), midiBytes);

            EmbeddedSynth synth = activateFluidSynth();
            synth.generateWavFile(midiFile, wavFile);
            String wavBase64 = Base64.getEncoder().encodeToString(Files.readAllBytes(wavFile.toPath()));

            return new AudioRenderResponse(
                    preview.songName(),
                    preview.tempo(),
                    preview.style(),
                    wavBase64,
                    warnings
            );
        } catch (Exception ex) {
            throw new UnsupportedOperationException("Audio rendering failed: " + ex.getMessage(), ex);
        } finally {
            if (midiFile != null) midiFile.delete();
            if (wavFile != null) wavFile.delete();
        }
    }

    private ArrangementResponse buildWithToolkit(ArrangementRequest request, List<String> warnings) throws Exception {
        int tempo = request.tempo() == null || request.tempo() <= 0 ? 100 : request.tempo();
        TimeSignature timeSignature = resolveTimeSignature(request.timeSignature(), warnings);
        addRepoPathWarnings(warnings);
        if (request.repeatPlan() != null) {
            warnings.add("repeatPlan received but not yet mapped to JJazz first/second ending; using legacy section sequence.");
        }
        List<ChartBar> chartBars = buildSinglePassChartBars(request);
        if (chartBars.isEmpty()) {
            throw new IllegalArgumentException("No measures available to generate.");
        }

        Song song = SongFactory.getDefault().createEmptySong(
                safe(request.songName(), "Untitled"),
                chartBars.size(),
                "A",
                timeSignature,
                null
        );
        song.setTempo(tempo);

        populateChordLeadSheet(song, chartBars, timeSignature, warnings);

        File midiFile = Files.createTempFile("jjazz-preview-", ".mid").toFile();
        String midiBase64;
        RhythmInfo appliedRhythmInfo;
        RhythmDatabase rdb = RhythmDatabase.getSharedInstance();
        appliedRhythmInfo = pickRhythm(rdb, request.style(), timeSignature);
        if (appliedRhythmInfo == null) {
            appliedRhythmInfo = rdb.getDefaultRhythm(timeSignature);
            warnings.add("Requested style not found; using default rhythm: " + appliedRhythmInfo.name());
        }
        applyRhythm(song, appliedRhythmInfo);
        applyArrangementMode(song, request.arrangementMode(), warnings);
        try {
            MidiMix midiMix = createMidiMix(song);
            boolean exported = SongMidiExporter.songToMidiFile(song, midiMix, midiFile, null);
            if (!exported || midiFile.length() == 0) {
                throw new IllegalStateException("SongMidiExporter returned no MIDI output.");
            }
            byte[] midiBytes = Files.readAllBytes(midiFile.toPath());
            midiBase64 = Base64.getEncoder().encodeToString(midiBytes);
        } finally {
            midiFile.delete();
        }

        return new ArrangementResponse(
                safe(request.songName(), "Untitled"),
                tempo,
                appliedRhythmInfo.rhythmUniqueId(),
                List.of(),
                chartBars,
                midiBase64,
                warnings
        );
    }

    private ArrangementResponse fallbackToPreview(ArrangementRequest request, List<String> warnings) {
        var previewArrangementService = previewArrangementServiceProvider.getIfAvailable();
        if (previewArrangementService == null) {
            throw new UnsupportedOperationException("Preview fallback service is unavailable.");
        }
        var response = previewArrangementService.buildPreview(request);
        var combinedWarnings = new ArrayList<>(response.warnings());
        combinedWarnings.addAll(warnings);
        return new ArrangementResponse(
                response.songName(),
                response.tempo(),
                response.style(),
                response.previewNotes(),
                response.chartBars(),
                response.midiBase64(),
                combinedWarnings
        );
    }

    private void populateChordLeadSheet(Song song, List<ChartBar> chartBars, TimeSignature timeSignature, List<String> warnings)
            throws UnsupportedEditException {
        CLI_Factory cliFactory = CLI_Factory.getDefault();
        int beatsPerBar = Math.max(1, timeSignature.getUpper());
        for (int barIndex = 0; barIndex < chartBars.size(); barIndex++) {
            List<String> beatChords = parseBeatChords(chartBars.get(barIndex).source(), beatsPerBar);
            for (int beat = 0; beat < beatChords.size(); beat++) {
                String chord = beatChords.get(beat);
                if ("%".equals(chord)) {
                    continue;
                }
                try {
                    song.getChordLeadSheet().addItem(cliFactory.createChordSymbol(chord, barIndex, beat));
                } catch (ParseException ex) {
                    warnings.add("Invalid chord '" + chord + "' at bar " + (barIndex + 1) + ", beat " + (beat + 1));
                }
            }
        }
    }

    private void applyRhythm(Song song, RhythmInfo rhythmInfo)
            throws UnavailableRhythmException, UnsupportedEditException {
        Rhythm rhythm = RhythmDatabase.getSharedInstance().getRhythmInstance(rhythmInfo);
        song.getSongStructure().setSongPartsRhythm(song.getSongStructure().getSongParts(), rhythm, null);
    }

    private void applyArrangementMode(Song song, String arrangementMode, List<String> warnings) {
        if (arrangementMode == null || arrangementMode.isBlank()) {
            return;
        }
        List<SongPart> songParts = song.getSongStructure().getSongParts();
        if (songParts.isEmpty()) {
            warnings.add("arrangementMode was provided but song has no SongParts.");
            return;
        }
        var rhythm = songParts.getFirst().getRhythm();
        var variationRp = RP_SYS_Variation.getVariationRp(rhythm);
        var fillRp = RP_SYS_Fill.getFillRp(rhythm);
        String mode = arrangementMode.trim().toUpperCase(Locale.ROOT);
        boolean applied;
        switch (mode) {
            case "MAIN_A" -> applied = applyStateValue(songParts, variationRp, List.of("MAIN_A", "MAIN A", "A"), song, "MAIN_A", warnings);
            case "MAIN_B" -> applied = applyStateValue(songParts, variationRp, List.of("MAIN_B", "MAIN B", "B"), song, "MAIN_B", warnings);
            case "MAIN_C" -> applied = applyStateValue(songParts, variationRp, List.of("MAIN_C", "MAIN C", "C"), song, "MAIN_C", warnings);
            case "MAIN_D" -> applied = applyStateValue(songParts, variationRp, List.of("MAIN_D", "MAIN D", "D"), song, "MAIN_D", warnings);
            case "INTRO" -> applied = applyStateValue(songParts, variationRp, List.of("INTRO", "IN"), song, "INTRO", warnings);
            case "ENDING" -> applied = applyStateValue(songParts, variationRp, List.of("ENDING", "END", "OUTRO"), song, "ENDING", warnings);
            case "FILL" -> applied = applyStateValue(songParts, fillRp, List.of("FILL", "ON", "YES", "ALWAYS", "TRUE"), song, "FILL", warnings);
            default -> {
                warnings.add("Unsupported arrangementMode '" + arrangementMode + "'.");
                applied = false;
            }
        }
        if (!applied && !"FILL".equals(mode) && fillRp != null && ("INTRO".equals(mode) || "ENDING".equals(mode))) {
            applyStateValue(songParts, fillRp, List.of("FILL", "ON", "YES", "ALWAYS", "TRUE"), song, mode + " (fallback fill)", warnings);
        }
    }

    private boolean applyStateValue(
            List<SongPart> songParts,
            RP_State parameter,
            List<String> desiredTokens,
            Song song,
            String modeName,
            List<String> warnings
    ) {
        if (parameter == null) {
            warnings.add("arrangementMode=" + modeName + " is not available for this rhythm (missing parameter).");
            return false;
        }
        String selectedValue = pickBestStateValue(parameter.getPossibleValues(), desiredTokens);
        if (selectedValue == null) {
            warnings.add("arrangementMode=" + modeName + " not supported by rhythm values: " + parameter.getPossibleValues());
            return false;
        }
        for (SongPart songPart : songParts) {
            song.getSongStructure().setRhythmParameterValue(songPart, parameter, selectedValue);
        }
        return true;
    }

    private String pickBestStateValue(List<String> possibleValues, List<String> desiredTokens) {
        if (possibleValues == null || possibleValues.isEmpty()) {
            return null;
        }
        for (String desiredToken : desiredTokens) {
            String normalizedDesired = normalizeModeToken(desiredToken);
            for (String possibleValue : possibleValues) {
                if (normalizeModeToken(possibleValue).equals(normalizedDesired)) {
                    return possibleValue;
                }
            }
        }
        for (String desiredToken : desiredTokens) {
            String normalizedDesired = normalizeModeToken(desiredToken);
            for (String possibleValue : possibleValues) {
                if (normalizeModeToken(possibleValue).contains(normalizedDesired)
                        || normalizedDesired.contains(normalizeModeToken(possibleValue))) {
                    return possibleValue;
                }
            }
        }
        return null;
    }

    private String normalizeModeToken(String value) {
        if (value == null) {
            return "";
        }
        return value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private MidiMix createMidiMix(Song song) throws Exception {
        MidiMix midiMix = MidiMixManager.getDefault().createMix(song);
        var outputSynth = OutputSynthManager.getDefault().getDefaultOutputSynth();
        if (outputSynth != null) {
            outputSynth.fixInstruments(midiMix, true);
            outputSynth.getUserSettings().sendModeOnUponPlaySysexMessages();
        }
        midiMix.sendAllMidiMixMessages();
        midiMix.sendAllMidiVolumeMessages();
        return midiMix;
    }

    private RhythmInfo pickRhythm(RhythmDatabase rdb, String styleInput, TimeSignature timeSignature) {
        if (styleInput == null || styleInput.isBlank()) {
            return rdb.getDefaultRhythm(timeSignature);
        }
        String style = styleInput.trim();
        RhythmInfo exact = rdb.getRhythm(style);
        if (exact != null && exact.timeSignature().equals(timeSignature)) {
            return exact;
        }
        RhythmInfo found = rdb.findRhythm(style, ri -> ri.timeSignature().equals(timeSignature));
        if (found != null) {
            return found;
        }
        String lower = style.toLowerCase(Locale.ROOT);
        return rdb.getRhythms(timeSignature).stream()
                .filter(ri -> ri.rhythmUniqueId().toLowerCase(Locale.ROOT).contains(lower)
                        || ri.name().toLowerCase(Locale.ROOT).contains(lower))
                .min(Comparator.comparing(RhythmInfo::name))
                .orElse(null);
    }

    private TimeSignature resolveTimeSignature(String value, List<String> warnings) {
        try {
            if (value != null && !value.isBlank()) {
                TimeSignature parsed = TimeSignature.parse(value.trim());
                if (parsed != null) {
                    return parsed;
                }
            }
        } catch (ParseException ignored) {
            warnings.add("Unsupported time signature '" + value + "', fallback to 4/4.");
        }
        return TimeSignature.FOUR_FOUR;
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

    private List<String> parseBeatChords(String measureText, int beatsPerBar) {
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
        while (beats.size() < beatsPerBar) {
            beats.add(lastChord);
        }
        return beats.subList(0, Math.min(beatsPerBar, beats.size()));
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

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeStyleValue(String style) {
        return Objects.requireNonNullElse(style, "").isBlank() ? TOOLKIT_STYLE_HINTS.getFirst() : style;
    }

    private EmbeddedSynth activateFluidSynth() throws EmbeddedSynthException {
        String resolvedSoundfontPath = resolveSoundfontPath();
        if (resolvedSoundfontPath.isBlank()) {
            throw new UnsupportedOperationException(
                    "No SoundFont path configured. Set jjazz.soundfont.path or JJAZZ_SOUNDFONT_PATH, " +
                            "or set jjazz.toolkit.repo.path / JJAZZ_TOOLKIT_REPO_PATH so demo/JJazzLab-SoundFont.sf2 can be auto-detected."
            );
        }
        File soundFontFile = new File(resolvedSoundfontPath);
        if (!soundFontFile.exists()) {
            throw new UnsupportedOperationException("SoundFont file not found: " + resolvedSoundfontPath);
        }
        var synthProvider = EmbeddedSynthProvider.getDefaultProvider();
        if (synthProvider == null) {
            throw new UnsupportedOperationException("No EmbeddedSynthProvider found.");
        }
        EmbeddedSynth synth = synthProvider.getEmbeddedSynth();
        synth.configure(soundFontFile);
        synthProvider.setEmbeddedSynthActive(true);
        return synth;
    }

    private String resolveSoundfontPath() {
        if (!soundFontPath.isBlank()) {
            return soundFontPath;
        }
        if (!toolkitRepoPath.isBlank()) {
            Path derived = Path.of(toolkitRepoPath).resolve("demo").resolve("JJazzLab-SoundFont.sf2");
            if (Files.exists(derived)) {
                return derived.toString();
            }
        }
        return "";
    }

    private void addRepoPathWarnings(List<String> warnings) {
        if (!toolkitRepoPath.isBlank() && !Files.isDirectory(Path.of(toolkitRepoPath))) {
            warnings.add("jjazz.toolkit.repo.path does not exist: " + toolkitRepoPath);
        }
        if (!coreRepoPath.isBlank() && !Files.isDirectory(Path.of(coreRepoPath))) {
            warnings.add("jjazz.core.repo.path does not exist: " + coreRepoPath);
        }
    }
}
