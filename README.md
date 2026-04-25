# Music System MVP

This MVP includes:

- `frontend`: score input + playback UI (browser)
- `backend`: Spring Boot API for arrangement preview and MIDI export

## Run backend

```bash
cd backend
mvn spring-boot:run
```

Backend URL: `http://localhost:8080`

## Open frontend

Frontend static files are served by Spring Boot.

- App URL: `http://localhost:8080`

## Available API

- `POST /api/arrangements/preview`
- `POST /api/arrangements/render-audio`
- `GET /api/arrangements/rhythms`
- `GET /api/arrangements/soundfonts`

For full request/response contracts and integration flow, see
`docs/integration-api.md`.

## Multi-repo setup (recommended)

Use 3 sibling repositories under one parent directory:

```text
~/workspace/
  music-system/
  JJazzLabToolkit-main/
  JJazzLab-master/
```

Backend reads these environment variables:

- `ARRANGEMENT_ENGINE` (default `jjazz`)
- `JJAZZ_SOUNDFONT_PATH` (optional explicit `.sf2` path)
- `JJAZZ_TOOLKIT_REPO_PATH` (path to `JJazzLabToolkit-main`)
- `JJAZZ_CORE_REPO_PATH` (path to `JJazzLab-master`)

If `JJAZZ_SOUNDFONT_PATH` is empty and `JJAZZ_TOOLKIT_REPO_PATH` is set, backend auto-uses:
`$JJAZZ_TOOLKIT_REPO_PATH/demo/JJazzLab-SoundFont.sf2`.

Linux example:

```bash
export ARRANGEMENT_ENGINE=jjazz
export JJAZZ_TOOLKIT_REPO_PATH=$HOME/workspace/JJazzLabToolkit-main
export JJAZZ_CORE_REPO_PATH=$HOME/workspace/JJazzLab-master
# Optional if you keep a custom SoundFont:
# export JJAZZ_SOUNDFONT_PATH=/path/to/your.sf2
cd ~/workspace/music-system/backend
mvn spring-boot:run
```

### Preview request example

```json
{
  "songName": "Autumn Practice",
  "timeSignature": "4/4",
  "tempo": 110,
  "style": "Swing4",
  "keySignature": "C",
  "repeatMarkers": "A A B A",
  "songRepeats": 2,
  "sections": [
    {
      "name": "A",
      "measures": ["C //B7", "Dm7 G7 Cmaj7 /", "Em7 A7 Dm7 G7", "C / / /"]
    },
    {
      "name": "B",
      "measures": ["Am7 D7 Gmaj7 /", "F#m7b5 B7 Em7 /"]
    }
  ]
}
```

### Beat placeholder notation

- `/` means hold previous chord for one beat.
- `C //B7` in 4/4 means C on beat 1-3, B7 on beat 4.
- `songRepeats` repeats the full form for generation, but chart preview stays single-pass lead sheet.

## JJazz Toolkit integration (next step)

Current `PreviewArrangementService` is a placeholder generator so you can input chart and hear immediate playback.

To switch to real JJazz auto-accompaniment:

1. Add `org.jjazzlab.toolkit:jjazzlab-toolkit` dependency.
2. Initialize rhythm database and toolkit service providers at startup.
3. Convert request data to JJazz `Song` + `ChordLeadSheet`.
4. Select rhythm/style and run generation pipeline.
5. Return generated MIDI/WAV output and parsed note events for frontend visualization.
