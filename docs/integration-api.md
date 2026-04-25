# Music System Integration API

This document defines the API contract for integrating external frontends with `music-system`.

## Base URL

- Local default: `http://localhost:8080`
- Content type: `application/json`
- CORS: enabled (`*`)

## Endpoints

- `POST /api/arrangements/preview`
  - Build a chart preview and MIDI preview data.
- `POST /api/arrangements/render-audio`
  - Render arrangement audio and return WAV data.
- `GET /api/arrangements/rhythms?timeSignature=4/4`
  - Return supported style names. `timeSignature` is optional.
- `GET /api/arrangements/soundfonts`
  - Return available soundfont paths/names.

## Request Contract

`POST /preview` and `POST /render-audio` use the same request schema:

```json
{
  "songName": "string",
  "timeSignature": "4/4",
  "tempo": 110,
  "style": "Swing4",
  "soundfontPath": "string",
  "keySignature": "C",
  "transposeSemitones": 0,
  "arrangementMode": "string",
  "repeatMarkers": "A A B A",
  "songRepeats": 2,
  "measures": ["C //B7", "Dm7 G7 Cmaj7 /"],
  "sections": [
    {
      "name": "A",
      "measures": ["C //B7", "Dm7 G7 Cmaj7 /"]
    }
  ],
  "repeatPlan": {
    "blocks": [
      {
        "label": "A",
        "passes": ["A", "A'"],
        "repeats": 2
      }
    ]
  }
}
```

Notes:

- At least one of `measures` or `sections` must be provided.
- `repeatPlan` is optional advanced repeat metadata.
- Unknown/unused fields are ignored by frontend but should not be sent unless needed.

## Response Contract

### `POST /api/arrangements/preview` -> `ArrangementResponse`

```json
{
  "songName": "Autumn Practice",
  "tempo": 110,
  "style": "Swing4",
  "previewNotes": [
    {
      "startBeat": 0.0,
      "durationBeat": 1.0,
      "midi": 60,
      "velocity": 90
    }
  ],
  "chartBars": [
    {
      "section": "A",
      "repeatIndex": 1,
      "barIndexInSection": 1,
      "source": "C //B7"
    }
  ],
  "midiBase64": "base64-midi-data",
  "warnings": []
}
```

### `POST /api/arrangements/render-audio` -> `AudioRenderResponse`

```json
{
  "songName": "Autumn Practice",
  "tempo": 110,
  "style": "Swing4",
  "wavBase64": "base64-wav-data",
  "warnings": []
}
```

### `GET /api/arrangements/rhythms` response

```json
["Swing4", "Bossa", "Pop8"]
```

### `GET /api/arrangements/soundfonts` response

```json
["/path/to/JJazzLab-SoundFont.sf2"]
```

## Validation and Errors

### Missing chart content

When both `measures` and `sections` are empty or missing:

- HTTP status: `400`
- Message: `At least one measure is required.`

Typical Spring error envelope example:

```json
{
  "timestamp": "2026-04-25T09:00:00.000+00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "At least one measure is required.",
  "path": "/api/arrangements/preview"
}
```

### Unsupported audio rendering mode

`POST /render-audio` may return:

- HTTP status: `400`
- Message from backend unsupported operation

Frontend should treat any non-2xx response as failure and show server message if available.

## Measure Notation Rules

- `/` means "hold previous chord for one beat".
- Example: `C //B7` in `4/4` means C for beat 1-3, B7 for beat 4.
- `songRepeats` affects generation pass count, while chart preview may remain single-pass lead-sheet style.

## Recommended Frontend Flow

1. On init, call `GET /api/arrangements/rhythms` to populate style options.
2. Optionally call `GET /api/arrangements/soundfonts` to populate soundfont options.
3. On preview action, call `POST /api/arrangements/preview`.
4. On render action, call `POST /api/arrangements/render-audio`.
5. Decode `midiBase64`/`wavBase64` into `Blob` for playback.
6. Surface `warnings` to user as non-blocking messages.

## Local Environment Notes

Backend environment variables:

- `ARRANGEMENT_ENGINE` (default `jjazz`)
- `JJAZZ_SOUNDFONT_PATH` (optional explicit `.sf2` path)
- `JJAZZ_TOOLKIT_REPO_PATH` (path to `JJazzLabToolkit-main`)
- `JJAZZ_CORE_REPO_PATH` (path to `JJazzLab-master`)
