const sectionsContainer = document.getElementById("sections");
const warningsEl = document.getElementById("warnings");
const statusEl = document.getElementById("status");
const previewSummaryEl = document.getElementById("previewSummary");
const chartPreviewEl = document.getElementById("chartPreview");
const addSectionBtn = document.getElementById("addSectionBtn");
const generateBtn = document.getElementById("generateBtn");
const playBtn = document.getElementById("playBtn");
const stopBtn = document.getElementById("stopBtn");
const downloadMidiBtn = document.getElementById("downloadMidiBtn");
const apiBase = window.location.origin;
const styleSelect = document.getElementById("style");
const timeSignatureSelect = document.getElementById("timeSignature");
const arrangementModeSelect = document.getElementById("arrangementMode");
const soundfontSelect = document.getElementById("soundfontPath");
const tabInputBtn = document.getElementById("tabInputBtn");
const tabPlaybackBtn = document.getElementById("tabPlaybackBtn");
const tabInputPane = document.getElementById("tabInputPane");
const tabPlaybackPane = document.getElementById("tabPlaybackPane");

const initialSections = [
    { name: "A", measures: ["Cmaj7", "Cmaj7", "D7", "D7", "D-7", "G7", "Cmaj7", "G7"] },
    { name: "A'", measures: ["Cmaj7", "Cmaj7", "D7", "D7", "D-7", "G7", "Cmaj7", "G-7 C7"] },
    { name: "B", measures: ["Fmaj7", "Fmaj7", "Fmaj7", "Fmaj7", "D7", "D7", "D-7", "G7"] }
];
let currentResponse = null;
let synth = null;
let audioPlayer = null;
const DEFAULT_ARRANGEMENT_MODES = [
    { value: "MAIN_A", label: "主段 A" },
    { value: "MAIN_B", label: "主段 B" },
    { value: "MAIN_C", label: "主段 C" },
    { value: "MAIN_D", label: "主段 D" },
    { value: "FILL", label: "過門 Fill" },
    { value: "ENDING", label: "尾奏 Ending" }
];

function createBarRow(value = "") {
    const row = document.createElement("div");
    row.className = "barRow";
    const indexEl = document.createElement("span");
    const inputEl = document.createElement("input");
    inputEl.value = value;
    inputEl.placeholder = "例如: C //B7 或 Dm7 G7 Cmaj7 /";
    const removeBtn = document.createElement("button");
    removeBtn.type = "button";
    removeBtn.textContent = "刪除";
    removeBtn.className = "danger";
    removeBtn.addEventListener("click", () => {
        row.remove();
        refreshBarIndexes();
    });
    row.append(indexEl, inputEl, removeBtn);
    return row;
}

function refreshBarIndexes() {
    [...sectionsContainer.querySelectorAll(".sectionCard")].forEach((sectionCard) => {
        const barRows = sectionCard.querySelectorAll(".barRow");
        barRows.forEach((row, idx) => {
            row.querySelector("span").textContent = `${idx + 1}`;
        });
    });
}

function nextDuplicateSectionName(baseName) {
    const normalized = (baseName || "A").trim() || "A";
    const existing = new Set(
        [...sectionsContainer.querySelectorAll(".sectionName")]
            .map((input) => input.value.trim())
            .filter(Boolean)
    );
    let candidate = `${normalized}'`;
    while (existing.has(candidate)) {
        candidate += "'";
    }
    return candidate;
}

function addSection(section = { name: "A", measures: ["C / / /"] }) {
    const card = document.createElement("div");
    card.className = "sectionCard";

    const header = document.createElement("div");
    header.className = "sectionHeader";
    const title = document.createElement("input");
    title.value = section.name;
    title.placeholder = "段落名稱";
    title.className = "sectionName";
    const addBarButton = document.createElement("button");
    addBarButton.type = "button";
    addBarButton.textContent = "新增小節";
    const duplicateSectionButton = document.createElement("button");
    duplicateSectionButton.type = "button";
    duplicateSectionButton.textContent = "複製段落";
    const removeSectionButton = document.createElement("button");
    removeSectionButton.type = "button";
    removeSectionButton.textContent = "刪除段落";
    removeSectionButton.className = "danger";
    removeSectionButton.addEventListener("click", () => {
        card.remove();
        refreshBarIndexes();
    });
    duplicateSectionButton.addEventListener("click", () => {
        const measures = [...bars.querySelectorAll(".barRow input")].map((input) => input.value);
        const duplicate = addSection({
            name: nextDuplicateSectionName(title.value),
            measures: measures.length ? measures : ["C / / /"]
        });
        card.insertAdjacentElement("afterend", duplicate);
        refreshBarIndexes();
    });

    header.append("段落", title, addBarButton, duplicateSectionButton, removeSectionButton);

    const bars = document.createElement("div");
    bars.className = "bars";
    for (const value of section.measures || []) {
        bars.appendChild(createBarRow(value));
    }

    addBarButton.addEventListener("click", () => {
        bars.appendChild(createBarRow(""));
        refreshBarIndexes();
    });

    card.append(header, bars);
    sectionsContainer.appendChild(card);
    refreshBarIndexes();
    return card;
}

function collectSections() {
    return [...sectionsContainer.querySelectorAll(".sectionCard")]
        .map((card) => {
            const measures = [...card.querySelectorAll(".barRow input")]
                .map((input) => input.value.trim())
                .filter(Boolean);
            return {
                name: card.querySelector(".sectionName").value.trim() || "Section",
                measures
            };
        })
        .filter((s) => s.measures.length > 0);
}

function showWarnings(warnings) {
    warningsEl.innerHTML = "";
    for (const warning of warnings || []) {
        const li = document.createElement("li");
        li.textContent = warning;
        warningsEl.appendChild(li);
    }
}

function renderChart(chartBars) {
    chartPreviewEl.innerHTML = "";
    if (!chartBars || chartBars.length === 0) {
        return;
    }
    const sequenceBlocks = [];
    let currentBlock = [];
    for (const bar of chartBars) {
        if (currentBlock.length === 0) {
            currentBlock.push(bar);
            continue;
        }
        const previousBar = currentBlock[currentBlock.length - 1];
        const startsNewOccurrence = bar.barIndexInSection === 1;
        if (bar.section !== previousBar.section || startsNewOccurrence) {
            sequenceBlocks.push(currentBlock);
            currentBlock = [bar];
        } else {
            currentBlock.push(bar);
        }
    }
    if (currentBlock.length > 0) {
        sequenceBlocks.push(currentBlock);
    }

    for (const bars of sequenceBlocks) {
        const sectionName = bars[0].section;
        const sectionBlock = document.createElement("div");
        sectionBlock.className = "sectionBlock";

        const marker = document.createElement("span");
        marker.className = "sectionMarker";
        marker.textContent = sectionName;
        sectionBlock.appendChild(marker);

        const sectionBars = document.createElement("div");
        sectionBars.className = "sectionBars";
        let currentRow = null;

        bars.forEach((bar, idx) => {
            if (idx % 4 === 0) {
                currentRow = document.createElement("div");
                currentRow.className = "sectionRow";
                sectionBars.appendChild(currentRow);
            }
            const barCell = document.createElement("div");
            barCell.className = "barCell";
            if (idx % 4 === 0) {
                barCell.classList.add("firstInRow");
            }
            if (idx % 4 === 3 || idx === bars.length - 1) {
                barCell.classList.add("lastInRow");
            }

            const beats = parseMeasureToFourBeats(bar.source);
            const displayBeats = compressBeatsForDisplay(beats);
            const beatRow = document.createElement("div");
            beatRow.className = "beatRow";
            displayBeats.forEach((beatText) => {
                const beatCell = document.createElement("div");
                beatCell.className = "beatCell";
                if (beatText) {
                    const chord = renderChordToken(beatText);
                    beatCell.appendChild(chord);
                }
                beatRow.appendChild(beatCell);
            });

            barCell.append(beatRow);
            currentRow.appendChild(barCell);
        });
        sectionBlock.appendChild(sectionBars);
        chartPreviewEl.appendChild(sectionBlock);
    }
}

function renderChordToken(token) {
    const chord = document.createElement("div");
    chord.className = "chartChord";

    const slashIndex = token.indexOf("/");
    const main = slashIndex >= 0 ? token.slice(0, slashIndex) : token;
    const bass = slashIndex >= 0 ? token.slice(slashIndex + 1) : "";

    const match = main.match(/^([A-G])([#b]?)(.*)$/);
    const root = document.createElement("span");
    root.className = "chordRoot";
    root.textContent = match ? match[1] : main;
    chord.appendChild(root);

    const accidental = match ? match[2] : "";
    if (accidental) {
        const accidentalEl = document.createElement("span");
        accidentalEl.className = "chordAccidental";
        accidentalEl.textContent = accidental;
        chord.appendChild(accidentalEl);
    }

    const quality = match ? match[3] : "";
    if (quality) {
        const suffix = document.createElement("span");
        suffix.className = "chordSuffix";
        suffix.textContent = quality;
        chord.appendChild(suffix);
    }

    if (bass) {
        const slash = document.createElement("span");
        slash.className = "chordSlash";
        slash.textContent = `/${bass}`;
        chord.appendChild(slash);
    }

    const styleProfile = estimateTokenChordStyle(token);
    chord.style.setProperty("--chord-size", `${styleProfile.size}px`);
    chord.style.setProperty("--chord-min-height", `${styleProfile.minHeight}px`);
    chord.style.setProperty("--chord-scale-x", `${styleProfile.scaleX}`);
    chord.style.setProperty("--chord-root-weight", `${styleProfile.rootWeight}`);
    chord.style.setProperty("--chord-root-scale-x", `${styleProfile.rootScaleX}`);
    chord.style.setProperty("--chord-acc-weight", `${styleProfile.accWeight}`);
    chord.style.setProperty("--chord-acc-scale-x", `${styleProfile.accScaleX}`);
    return chord;
}

function parseMeasureToFourBeats(source) {
    const tokens = (source || "").trim().split(/\s+/).filter(Boolean);
    const hasSlashMarker = tokens.some((token) => token === "/" || token.startsWith("/"));
    if (tokens.length === 2 && !hasSlashMarker) {
        return [tokens[0], tokens[0], tokens[1], tokens[1]];
    }
    const beats = [];
    let lastChord = "C";
    for (const token of tokens) {
        if (token === "/") {
            beats.push(lastChord);
            continue;
        }
        if (token.startsWith("/")) {
            const slashCount = token.match(/^\/+/)?.[0]?.length || 0;
            for (let i = 0; i < slashCount; i++) beats.push(lastChord);
            const chordPart = token.slice(slashCount);
            if (chordPart) {
                lastChord = chordPart;
                beats.push(lastChord);
            }
            continue;
        }
        lastChord = token;
        beats.push(lastChord);
    }
    while (beats.length < 4) beats.push(lastChord);
    return beats.slice(0, 4);
}

function compressBeatsForDisplay(beats) {
    if (!beats || beats.length === 0) return ["", "", "", ""];
    const compressed = [];
    let previous = null;
    for (const beat of beats) {
        if (beat === previous) {
            compressed.push("");
        } else {
            compressed.push(beat);
            previous = beat;
        }
    }
    return compressed;
}

function estimateTokenChordStyle(token) {
    const len = (token || "").length;
    if (len <= 3) {
        return { size: 42, minHeight: 44, scaleX: 0.92, rootWeight: 430, rootScaleX: 0.9, accWeight: 400, accScaleX: 0.86 };
    }
    if (len <= 5) {
        return { size: 36, minHeight: 40, scaleX: 0.89, rootWeight: 410, rootScaleX: 0.87, accWeight: 380, accScaleX: 0.83 };
    }
    if (len <= 7) {
        return { size: 31, minHeight: 36, scaleX: 0.86, rootWeight: 390, rootScaleX: 0.84, accWeight: 360, accScaleX: 0.8 };
    }
    return { size: 28, minHeight: 34, scaleX: 0.83, rootWeight: 370, rootScaleX: 0.81, accWeight: 340, accScaleX: 0.77 };
}

function renderPreview(response) {
    const notes = response?.previewNotes || [];
    const chartBars = response?.chartBars || [];
    const displayBars = chartBars.some((b) => (b.repeatIndex || 1) > 1)
        ? chartBars.filter((b) => (b.repeatIndex || 1) === 1)
        : chartBars;
    renderChart(displayBars);
    if (notes.length === 0) {
        previewSummaryEl.textContent = `已生成 ${displayBars.length} 小節 / 純 MIDI 模式`;
        return;
    }
    previewSummaryEl.textContent = `已生成 ${displayBars.length} 小節 / ${notes.length} 音符事件`;
}

async function loadStyles() {
    const ts = encodeURIComponent(timeSignatureSelect.value || "");
    const response = await fetch(`${apiBase}/api/arrangements/rhythms?timeSignature=${ts}`);
    const styles = response.ok ? await response.json() : ["PopBallad"];
    styleSelect.innerHTML = "";
    for (const style of styles) {
        const option = document.createElement("option");
        option.value = style;
        option.textContent = style;
        styleSelect.appendChild(option);
    }
    const preferredStyle = styles.find((s) => s.toLowerCase() === "jjswing")
        || styles.find((s) => s.toLowerCase().includes("jjswing"))
        || styles.find((s) => s.toLowerCase().includes("swing"));
    if (preferredStyle) {
        styleSelect.value = preferredStyle;
    }
    loadArrangementModes(styleSelect.value);
    if (arrangementModeSelect.querySelector("option[value='MAIN_C']")) {
        arrangementModeSelect.value = "MAIN_C";
    }
}

function toSoundfontLabel(path) {
    if (!path) return "(default)";
    const normalized = path.replace(/\\/g, "/");
    return normalized.split("/").pop() || path;
}

async function loadSoundfonts() {
    if (!soundfontSelect) return;
    const response = await fetch(`${apiBase}/api/arrangements/soundfonts`);
    const soundfonts = response.ok ? await response.json() : [];
    soundfontSelect.innerHTML = "";
    if (!soundfonts.length) {
        const option = document.createElement("option");
        option.value = "";
        option.textContent = "使用系統預設";
        soundfontSelect.appendChild(option);
        return;
    }
    for (const path of soundfonts) {
        const option = document.createElement("option");
        option.value = path;
        option.textContent = toSoundfontLabel(path);
        option.title = path;
        soundfontSelect.appendChild(option);
    }
}

function loadArrangementModes(style) {
    if (!arrangementModeSelect) return;
    arrangementModeSelect.innerHTML = "";
    // Keep room for style-specific mapping in future.
    const modes = [...DEFAULT_ARRANGEMENT_MODES];
    if ((style || "").toLowerCase().includes("swing")) {
        modes.unshift({ value: "INTRO", label: "前奏 Intro" });
    }
    for (const mode of modes) {
        const option = document.createElement("option");
        option.value = mode.value;
        option.textContent = mode.label;
        arrangementModeSelect.appendChild(option);
    }
}

function setActiveTab(tab) {
    const isInput = tab === "input";
    tabInputBtn?.classList.toggle("active", isInput);
    tabPlaybackBtn?.classList.toggle("active", !isInput);
    tabInputPane?.classList.toggle("active", isInput);
    tabPlaybackPane?.classList.toggle("active", !isInput);
}

async function generatePreview() {
    const sections = collectSections();
    if (sections.length === 0) {
        statusEl.textContent = "請至少新增一個段落與小節";
        return;
    }
    const payload = {
        songName: document.getElementById("songName").value,
        timeSignature: document.getElementById("timeSignature").value,
        tempo: Number(document.getElementById("tempo").value),
        style: document.getElementById("style").value,
        soundfontPath: soundfontSelect?.value || "",
        keySignature: document.getElementById("keySignature").value,
        arrangementMode: arrangementModeSelect?.value || "MAIN_A",
        repeatMarkers: document.getElementById("repeatMarkers").value,
        songRepeats: Number(document.getElementById("songRepeats").value) || 1,
        measures: sections.flatMap((s) => s.measures),
        sections
    };

    statusEl.textContent = "生成中...";
    const response = await fetch(`${apiBase}/api/arrangements/preview`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
    });
    if (!response.ok) {
        const errorText = await response.text();
        throw new Error(errorText || `HTTP ${response.status}`);
    }
    currentResponse = await response.json();
    try {
        const audioResponse = await fetch(`${apiBase}/api/arrangements/render-audio`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(payload)
        });
        if (audioResponse.ok) {
            const audioData = await audioResponse.json();
            currentResponse.wavBase64 = audioData.wavBase64;
            currentResponse.warnings = [...(currentResponse.warnings || []), ...(audioData.warnings || [])];
        } else {
            currentResponse.warnings = [
                ...(currentResponse.warnings || []),
                `WAV 渲染失敗（HTTP ${audioResponse.status}），已保留 MIDI 預覽模式。`
            ];
        }
    } catch (error) {
        // Keep preview usable even if audio rendering fails.
        currentResponse.warnings = [
            ...(currentResponse.warnings || []),
            `WAV 渲染失敗：${error?.message || "未知錯誤"}，已保留 MIDI 預覽模式。`
        ];
    }
    showWarnings(currentResponse.warnings);
    renderPreview(currentResponse);
    statusEl.textContent = `完成：${currentResponse.songName} | ${currentResponse.style} | ${currentResponse.tempo} BPM`;
    setActiveTab("playback");
}

function midiToFrequency(midi) {
    return 440 * Math.pow(2, (midi - 69) / 12);
}

async function playPreview() {
    if (currentResponse?.wavBase64) {
        stopPreview();
        const bytes = atob(currentResponse.wavBase64);
        const data = new Uint8Array(bytes.length);
        for (let i = 0; i < bytes.length; i++) data[i] = bytes.charCodeAt(i);
        const blob = new Blob([data], { type: "audio/wav" });
        const url = URL.createObjectURL(blob);
        audioPlayer = new Audio(url);
        audioPlayer.onended = () => {
            URL.revokeObjectURL(url);
            audioPlayer = null;
            statusEl.textContent = "播放完成";
        };
        await audioPlayer.play();
        statusEl.textContent = "播放中（WAV）...";
        return;
    }
    if (!currentResponse?.previewNotes?.length) {
        if (currentResponse?.midiBase64) {
            statusEl.textContent = "純 MIDI 模式：請下載 MIDI 於播放器中播放。";
            return;
        }
        statusEl.textContent = "請先生成預覽";
        return;
    }
    await Tone.start();
    Tone.Transport.cancel();
    Tone.Transport.stop();
    Tone.Transport.seconds = 0;
    const bpm = currentResponse.tempo || 100;
    const secPerBeat = 60 / bpm;
    if (!synth) {
        synth = new Tone.PolySynth(Tone.Synth).toDestination();
    }
    currentResponse.previewNotes.forEach((note) => {
        Tone.Transport.schedule((time) => {
            synth.triggerAttackRelease(
                midiToFrequency(note.midi),
                Math.max(0.05, note.durationBeat * secPerBeat),
                time,
                Math.min(1, Math.max(0.2, note.velocity / 127))
            );
        }, note.startBeat * secPerBeat);
    });
    Tone.Transport.start("+0.05");
    statusEl.textContent = "播放中...";
}

function stopPreview() {
    if (audioPlayer) {
        audioPlayer.pause();
        audioPlayer.currentTime = 0;
        audioPlayer = null;
    }
    Tone.Transport.stop();
    Tone.Transport.cancel();
    statusEl.textContent = "已停止";
}

function downloadMidi() {
    if (!currentResponse?.midiBase64) {
        statusEl.textContent = "沒有 MIDI 可下載，請先生成";
        return;
    }
    const bytes = atob(currentResponse.midiBase64);
    const data = new Uint8Array(bytes.length);
    for (let i = 0; i < bytes.length; i++) {
        data[i] = bytes.charCodeAt(i);
    }
    const blob = new Blob([data], { type: "audio/midi" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${currentResponse.songName || "song"}.mid`;
    link.click();
    URL.revokeObjectURL(url);
}

for (const section of initialSections) addSection(section);
loadSoundfonts().catch(() => {
    if (soundfontSelect) {
        soundfontSelect.innerHTML = `<option value="">使用系統預設</option>`;
    }
});
loadStyles().catch(() => {
    styleSelect.innerHTML = `<option value="PopBallad">PopBallad</option>`;
});
timeSignatureSelect.addEventListener("change", () => {
    loadStyles().catch(() => {
        styleSelect.innerHTML = `<option value="PopBallad">PopBallad</option>`;
    });
});
styleSelect.addEventListener("change", () => loadArrangementModes(styleSelect.value));
tabInputBtn?.addEventListener("click", () => setActiveTab("input"));
tabPlaybackBtn?.addEventListener("click", () => setActiveTab("playback"));

addSectionBtn.addEventListener("click", () => addSection({ name: "New", measures: ["C / / /"] }));
generateBtn.addEventListener("click", () => generatePreview().catch((e) => {
    statusEl.textContent = `生成失敗：${e.message}`;
}));
playBtn.addEventListener("click", () => playPreview().catch((e) => {
    statusEl.textContent = `播放失敗：${e.message}`;
}));
stopBtn.addEventListener("click", stopPreview);
downloadMidiBtn.addEventListener("click", downloadMidi);
