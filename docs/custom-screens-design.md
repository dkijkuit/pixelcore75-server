# Custom Screens — Design & Implementation Plan

Status: **Phase 1 implementing** (2026-08-22). Canonical spec for the `CUSTOM` screen type spanning
`pixelcore75-server` (this repo) and `pixelcore75-frontend`. The firmware repo is **untouched** in Phase 1.

## 1. Goal

Let users design their own screens — static pixel art, frame animations, text/shapes — in a browser
designer, with import/export of a portable design file (`.pxd`), rendered server-side and played on
panels through the existing static-frame / ANIM pipelines.

## 2. Locked decisions

| Decision | Choice |
|---|---|
| Phase 1 scope | Editor first: pixel editor → `.pxd` design doc → compiled to static frame or ANIM stream |
| Rendering truth | **Server only** (Java2D). The designer previews via a new HTTP endpoint, not a client-side renderer. The edit canvas itself is local pixel manipulation; overlay text on canvas is an approximation |
| Live content (Phase 2) | Hybrid: server re-render + re-publish timer now; ACMD slot-in later via a per-design capability field (reserved) |
| Sharing | Import/export `.pxd` files only — no gallery, no auth surface |
| Firmware | Zero changes in Phase 1 — multi-frame designs ride the existing ANIM pipeline (slots, `uploadId` content-hash caching, staging, LittleFS persistence all come free) |

## 3. The `.pxd` format (normative; v1 + v2)

A design is a single JSON document. Stored as a **string** inside the `CUSTOM` screen config
(`design` component) and exchanged verbatim by the preview endpoint and import/export files
(`*.pxd`). One parser (server) is authoritative; the frontend mirrors the schema in TS.

`schemaVersion` is `1` or `2`: **v2 = v1 + the three parametric layer types of §3.6** (nothing
else differs). A v2 design without parametric layers is exactly a v1 design. The parser accepts
both; parametric layer types are rejected in a v1 design ("requires schemaVersion 2"), and any
other version is rejected — that gate is what lets a future server roll forward safely.

### 3.1 Schema

```json
{
  "schemaVersion": 1,
  "name": "My design",
  "frameDelayMs": 100,
  "frames": [
    {
      "layers": [
        { "type": "bitmap", "data": "data:image/png;base64,...." },
        { "type": "text",   "text": "HELLO", "x": 2, "y": 2,  "color": "#00FF00", "font": "CG_PIXEL" },
        { "type": "rect",   "x": 10, "y": 5, "w": 8, "h": 3,  "color": "#FF0000", "filled": true },
        { "type": "line",   "x1": 0, "y1": 0, "x2": 63, "y2": 31, "color": "#FFFFFF" },
        { "type": "circle", "cx": 32, "cy": 16, "r": 5, "color": "#0000FF", "filled": false }
      ]
    }
  ]
}
```

- `schemaVersion`: must be exactly `1`. Any other value is rejected with a clear message
  (forward-compat: future versions gate on this).
- `name`: string, 1–64 chars. Used as filename stem on export.
- `frameDelayMs`: integer 10–65535, default `100` when absent. Used when `frames.length > 1`.
- `frames`: array of 1–60 frames (same screen-level cap as ANIMATION; wire cap 200 is not exposed in v1).
- Frame = `{ "layers": Layer[] }`. Layers composite **in array order** onto a black 64×32 canvas.
  The Phase 1 editor emits at most one `bitmap` layer, first; the server accepts any order/count.
- Colors: `#RRGGBB` (6 hex digits) everywhere.
- Text is ASCII-folded server-side (existing `LatinFoldService`), same as every other screen.

### 3.2 Layer types

| type | fields | semantics |
|---|---|---|
| `bitmap` | `data` (PNG data URL) | Full-canvas background image. Decoded image **must be exactly 64×32** or the design is invalid. Drawn at (0,0). |
| `text` | `text` (1–255 chars), `x`, `y`, `color`, `font` | `y` = **top of the glyph line box**; server computes the AWT baseline as `y + fontMetrics.getAscent()`. Drawn with `PaintToolsService.drawText`. |
| `rect` | `x`, `y`, `w`, `h`, `color`, `filled` | `w`,`h` ≥ 1. Outline or fill. |
| `line` | `x1`, `y1`, `x2`, `y2`, `color` | 1 px line. |
| `circle` | `cx`, `cy`, `r`, `color`, `filled` | `r` ≥ 0. Outline or fill. |
| `sweep` (v2) | see §3.6 | Parametric: rotating radar-style line. |
| `scroll` (v2) | see §3.6 | Parametric: ping-pong marquee text clipped to a region. |
| `blink` (v2) | see §3.6 | Parametric: region alternates content ↔ black. |

All coordinates are integers; drawing **clips** to the canvas silently (negative/overshoot allowed).

### 3.3 Font ids (v1 fixed registry → `PaintConfig` beans)

| pxd `font` | Bean | File |
|---|---|---|
| `CG_PIXEL` (default when absent) | `cgPixel5Px` | cg-pixel-4x5.ttf |
| `MINI_LINE` | `miniLineFont8Px` | MiniLine2.ttf |
| `HABBO` | `habboFont8Px` | Habbo.ttf |
| `LED_BOARD` | `ledBoardFont8Px` | EXEPixelPerfect.ttf |
| `TINY` | `tinyUnicode8Px` | TinyUnicode.ttf |
| `FROST` | `frostFont4Px` | frostfont-logo.ttf |
| `GRINCHED` | `grinched7Px` | grinched-4x7.ttf |

Unknown font id → design invalid.

### 3.4 Validation rules (enforced identically at save, render, and preview)

1. Valid JSON object; unknown properties at **any** level are **ignored** (lenient parse — lets v1
   files round-trip reserved future sections like `params`/`bindings`).
2. `schemaVersion` ∈ {1, 2}; `name` 1–64 chars; `frameDelayMs` (if present) 10–65535.
3. `frames` present, 1–60 entries, each with a (possibly empty) `layers` array.
4. Every layer has a known `type`; per-type rules from §3.2/§3.3/§3.6; colors match
   `^#[0-9a-fA-F]{6}$`.
5. `bitmap.data` decodes (server-side) to exactly 64×32.
6. Whole `design` string ≤ 512 KB.
7. Parametric layer types (`sweep`/`scroll`/`blink`) require `schemaVersion` 2. When a design
   contains **any** parametric layer, the whole design must be command-compilable (§3.6):
   exactly 1 frame, ≤ 4 parametric layers, ≤ 4 distinct fonts across `text`+`scroll` layers,
   every `bitmap` ≤ 16 distinct RGB565 colors, and **all layer coordinates in 0..255** (the ACMD
   u8 wire range; negative/overshoot coords stay legal only in frame-compiled designs, where the
   canvas clips silently).

### 3.5 Compile targets

- **1 frame** → static path: rendered to a 64×32 RGB565 retained base frame (like IMAGE).
- **2–60 frames** → frame path (`FrameScreenConfig`): streamed through the existing ANIM pipeline
  (PAL_RLE/RAW per frame, slot staging, `uploadId` content-hash skip, LittleFS persistence).
- **Parametric design (any parametric layer)** → **command path** (§3.6): one ACMD v1 batch
  published to `<serial>/cmd` while `pixelcore75.command-encoding.enabled` is on; the frame
  path's baked approximation (below) is the flag-off fallback, the static/thumbnail render, and
  the SSE fallback.
- Rendering is deterministic and time-independent in v1 (no bindings yet) — an unchanged design
  hashes identically and is never re-uploaded. A parametric design's *batch* is likewise
  deterministic (the panel owns the timeline), so an unchanged parametric design republishes the
  identical batch and, via the epoch carry-over, its parametrics run continuously.

### 3.6 Parametric layers (v2 — the ACMD compile target)

The three layer types map 1:1 onto the ACMD v1 parametric primitives the firmware ticks locally
(see the AGENTS.md ACMD section — this spec does not repeat the wire format):

```json
{ "type": "sweep",  "cx": 32, "cy": 16, "r": 15, "color": "#00FF00", "speedDegPerSec": 45 }
{ "type": "scroll", "x": 0, "y": 24, "w": 64, "h": 8, "text": "HELLO", "font": "CG_PIXEL",
  "color": "#FFFFFF", "speedMsPerPx": 120 }
{ "type": "blink",  "x": 28, "y": 12, "w": 8, "h": 8, "periodMs": 1000 }
```

| type | fields | constraints |
|---|---|---|
| `sweep` | `cx`, `cy`, `r`, `color`, `speedDegPerSec` | `r` ≥ 0; `speedDegPerSec` 1–255 (u8). Line from (`cx`,`cy`) to the rotated endpoint; θ = elapsed·speed/1000 mod 360, one full revolution per `360/speed` s. |
| `scroll` | `x`, `y`, `w`, `h`, `text`, `font`, `color`, `speedMsPerPx` | `w`,`h` ≥ 1; `text` 1–255 chars; `speedMsPerPx` 1–65535 (u16). Ping-pong marquee inside the region (the ACMD SCROLL pacing rules); a fitting text renders statically. `y` = glyph line-box top, same convention as `text`. |
| `blink` | `x`, `y`, `w`, `h`, `periodMs` | `w`,`h` ≥ 1; `periodMs` 2–65535 (u16). Alternates the **base content of the region** (all layers beneath it, composited) ↔ black every `periodMs/2`. |

Command compile (`CustomScreenService.renderCommandBatch`): black CLS, then the design's FONT
pages (hoisted to the front — they draw nothing, and ACMD requires them before their TEXT/SCROLL),
then every layer in array order (`bitmap`→BLIT, `text`→TEXT, `rect`→RECT/FILL, `line`→LINE,
`circle`→CIRC with r=0 as PIX, parametrics→SWEEP/SCROLL/BLINK). Text is Latin-folded and
non-ASCII-foldable characters are dropped (ACMD text is ASCII 32..126). TEXT/SCROLL y = the frame
path's baseline (`y + ascent`) `+ FontPage.lineTop()`, the same conversion the migrated screens
use, so both paths land on one baseline. Colors go through the same RGB565 quantization as frame
publishing. Golden-image parity of the baked t=0 pose vs the mirror is pinned by tests
(`CustomScreenCommandTests`, budget 160 px of 2048, measured 0).

Baked frame approximation (flag off, old firmware fleet, thumbnails, `renderScreen`, SSE
fallback): `sweep` → the θ=0 line (`cx,cy` → `cx+r,cy`); `scroll` → the folded text drawn
statically at `x`, clipped to the region (the marquee's head-hold pose); `blink` → no-op (the
content phase). Validation rule §3.4.7 guarantees every valid parametric design *can* compile to
commands; the approximation only ever runs where commands are unavailable.

Parametric overlays composite in **layer order** over the base each panel tick, exactly like the
ACMD overlay semantics (a later parametric draws over an earlier one where their regions
overlap). The per-batch cap is 4 armed parametrics (`AcmdOpcode.PARAMS_MAX`) — enforced at
validation, not silently truncated.

Preview: a parametric design's `POST /v1/screen/custom/preview` samples the batch through the
`AcmdMirror` (the exact Java model of the firmware engine) on a 100 ms tick for
`clamp(maxParametricLoopMs, 2000, 10000)` ms (sweep loop = 360/speed s; blink = `periodMs`;
scroll = its ping-pong cycle) — ≤ 100 frames returned at `frameDelayMs: 100`, the same response
shape as baked designs. The editor preview pane needs no changes.

## 4. Storage & wire contract (frontend ↔ server)

New screen type `CUSTOM`. Jackson is strict — the record surface is deliberately tiny so the pxd
schema can evolve without ever touching the cross-repo record contract again. Since the custom
screen **library** (§4.1), a rotation entry carries **exactly one** of two shapes:

```json
{ "screenType": "CUSTOM", "durationSeconds": 10, "customScreenId": 7 }
```

a reference to a user-owned library entry (`px75_custom_screen.custom_screen_id`), resolved to the
stored design at render time — library edits propagate live to every panel using the screen — or the
legacy inline shape from before the library existed:

```json
{ "screenType": "CUSTOM", "durationSeconds": 10, "design": "<the .pxd JSON as a string>" }
```

Java: `CustomScreenConfig(ScreenType screenType, int durationSeconds, Long customScreenId, String design)`
implementing `FrameScreenConfig` (add to its `permits`); a compact constructor enforces "exactly one
of the two set", and a delegating `@JsonCreator` accepts either JSON shape (strict mode's
fail-on-missing/null-creator-properties would otherwise reject both shapes). Serialization omits the
unset property (`@JsonInclude(NON_NULL)`), so stored jsonb round-trips shape-stable. `frameDelayMs()`
and `producesFrames()` delegate to the parsed design (`PxdDesign.parse(design)`) and throw
`IllegalStateException` on an unhydrated reference — the job always hydrates first (§5).
Frontend factory mirrors the exact same keys/types (`customScreenId?`, `design?`).

### 4.1 Custom screen library (user-owned, shareable)

Custom screens are bound to a **user**, not a panel: `px75_custom_screen` (`user_id`, `name`
denormalized from the design, `duration_seconds` default, `design` text, `shared` flag, `thumbnail`
= first frame PNG data URL regenerated at save, `updated_at`). REST under `/v1/screen/custom`
(`CustomScreenController` + `CustomScreenLibraryService`):

| Method + path | Rule |
|---|---|
| `GET /v1/screen/custom` | mine + others' shared (admin: all), no design |
| `GET /v1/screen/custom/{id}` | owner / shared / admin; includes `design` + `usageCount` |
| `POST /v1/screen/custom` `{design, durationSeconds, shared}` | mine; design parsed + dry-compiled (400 on invalid) |
| `PUT /v1/screen/custom/{id}` | owner or admin |
| `DELETE /v1/screen/custom/{id}` | owner or admin; rotations keep dangling refs (skipped at render) |

Render-time resolution (`CustomScreenLibraryService implements CustomScreenResolver`):
`PanelScreenJob.run()` hydrates the rotation each cycle — references become inline working copies
(the stored entity keeps its reference), dangling references are dropped with a warning, and an
all-dangling rotation shows the no-config image while re-checking every 30 s. No access checks at
render time: a rotation that referenced a shared screen keeps rendering after the owner unshares
it, until the panel config is saved again (`validateRotation` re-checks existence/visibility and
dry-compiles referenced designs at save time). Deleting/renaming is deliberate: the frontend shows
the reference count before delete.

## 5. Server implementation plan (this repo)

Follow the `add-screen-type` skill. Files:

1. `model/panel/config/ScreenType.java` — add `CUSTOM`.
2. `model/panel/config/CustomScreenConfig.java` — record above, implements `FrameScreenConfig`.
3. `model/panel/config/ScreenConfig.java` — add to `permits` **and** `@JsonSubTypes` (`name = "CUSTOM"`).
4. `model/panel/config/FrameScreenConfig.java` — add `CustomScreenConfig` to `permits`.
5. `service/screen/custom/PxdDesign.java` — record tree + lenient Jackson parse + explicit
   validation (§3.4) with a thrown `IllegalArgumentException` carrying a user-readable message.
   Bitmap decoding reuses `ImageService` helpers where practical.
6. `service/screen/custom/CustomScreenService.java` — `implements FrameScreenService<CustomScreenConfig>`:
   - `renderFrames` → compile each frame (layers in order onto `PaintToolsService.newImage()`).
   - `renderScreen` → compile frame 0 (static path + SSE fallback).
   - static shape drawing: plain `Graphics2D` on the 64×32 image (drawLine/drawRect/fillRect/
     drawOval/fillOval are all fine at this scale; this path is not ACMD-parity-constrained).
7. `service/job/PanelScreenJob.java` — exhaustive switch: `case CustomScreenConfig c -> ...`
   (static path); multi-frame routing is automatic via `FrameScreenConfig.producesFrames()`.
8. **Save-time validation**: on `POST /v1/panel/config`, parse + dry-compile every `CUSTOM`
   design → `400` with the parse message on failure (keeps garbage out of the DB and gives the
   frontend fast feedback).
9. **Preview endpoint** (new `controller/CustomScreenController.java`):

```
POST /v1/screen/custom/preview
Content-Type: application/json
{ "design": "<pxd JSON string>" }

200 → { "frames": ["data:image/png;base64,....", ...], "frameDelayMs": 100 }
400 → { "message": "frame 3: bitmap is 32x32, expected 64x32" }
```

   Frames are native 64×32 PNGs in play order (single frame allowed); the client upscales with
   `image-rendering: pixelated`. Reuses the SSE base64-PNG encoding helper if one exists.

10. Tests (infra is up, `./gradlew test` runs): `PxdDesign` parse/validate cases (valid minimal,
    unknown fields ignored, wrong schemaVersion, bad color, frames caps, font ids),
    `CustomScreenService` compile (bitmap-only, text top-of-line-box, multi-frame count/dims),
    controller test for the preview endpoint (200 + 400 paths).

## 6. Frontend implementation plan (`../pixelcore75-frontend`)

Follow the `add-screen-type` skill (registry trio + form). Files:

1. `src/types/pxd.ts` — TS mirror of §3: types, `DEFAULT_FONTS`, RGB565 quantize helper
   (`#RRGGBB → #RRGGBB` snapped to 5-6-5), `emptyDesign()` factory, `validatePxd()` client-side
   pre-check (same rules), `parseDesign()` safe wrapper.
2. `src/registry/screensRegistry.ts` — `createCustom` factory returning
   `{ screenType: 'CUSTOM', durationSeconds: 10, design: JSON.stringify(emptyDesign()) }`;
   `CUSTOM_DEF` (`format`: `custom: <name> · <n>f`); `Form` → async loader for `CustomForm.vue`.
3. `src/service/screens.ts` — `previewCustomDesign(design: string)` → POST §5.9, returns
   `{ frames: string[], frameDelayMs: number }`.
4. `src/components/screens/CustomForm.vue` — v-model contract like every other form. Holds the
   parsed design as editor state; emits config with `design: JSON.stringify(doc)` (debounced).
   Fields: design name, `durationSeconds`, `frameDelayMs`. Buttons: import `.pxd`, export `.pxd`.
5. `src/components/designer/` — the editor (v1 scope):
   - **PixelCanvas**: 64×32 grid at ~12× zoom, pointer-mapped cells, tools: brush, eraser
     (paints `#000000`), flood fill, line, rect (outline/filled), circle (outline/filled),
     eyedropper. Shape tools show a drag preview, bake on release. Paint edits mutate the frame's
     bitmap layer (canvas → `toDataURL('image/png')`, already RGB565-quantized colors).
   - **Overlays**: text elements (add/edit/delete; fields text/font/color; drag on canvas or
     arrow buttons to move). Text renders on-canvas as an approximation (monospace); the preview
     endpoint shows truth.
   - **FrameTimeline**: add / duplicate / delete / reorder frames, current index, **onion skin**
     (previous frame bitmap at ~30% alpha, editor-only), 1–60 enforced.
   - **PalettePicker**: 16 fixed RGB565-exact colors + custom color input with quantize feedback.
   - **Undo/redo** per frame (snapshot stack on stroke/shape/overlay change).
   - **Preview pane**: debounced (~500 ms) POST → cycle returned frames at `frameDelayMs` in a
     pixelated `<img>` (pattern from `AnimationForm.vue` playback); show server error messages.
   - Import/export: file picker → `JSON.parse` → `validatePxd` → replace state (snackbar errors);
     export downloads `<name>.pxd` (pretty-printed JSON).

The type dropdown, format column, and save path pick `CUSTOM` up automatically via the registry.

## 7. Phases 2–4 (outline only — not in this change)

- **Phase 2 — bindings + live render**: `bindings` on bitmap/text layers (`time.now`, `countdown.to`),
  `refreshMs` on the design → job re-renders + republishes on a timer (retained frame for static,
  re-upload only on content-hash change). Reserved `params` (per-instance knob values).
- **Phase 3 — feed bindings**: expose existing integrations (crypto/weather/F1/aircraft) as data
  sources; template = shared `.pxd` with unfilled params.
- **Phase 4 — ACMD compile target + custom user APIs**: ~~ACMD compile target~~ (delivered as
  pxd v2, §3.6 — parametric designs compile to command batches when the flag is on); remaining:
  user-defined HTTP sources, bindings.

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| pxd schema drift between TS and Java | This doc is normative; §10 checklist; server validation is the gate (400 messages surface drift immediately) |
| Strict Jackson rejects config | Record surface is 3 fixed fields; pxd lives inside one string — inner leniency is ours |
| Big designs bloat jsonb / MQTT | 512 KB design cap; ≤60 frames; ANIMATION already stores comparable inline base64; `uploadId` skip prevents re-uploads |
| Text overlay approximation misleads | Preview pane is server truth; canvas text is a positioned hint only |
| Garbage designs stored | Save-time dry-compile validation (§5.8) |

## 9. Verification

- Server: `./gradlew build -x test`, then `./gradlew test` (docker infra: Postgres + NanoMQ must be up).
- Frontend: `npm run type-check && npm run lint`.
- End-to-end (optional): save a CUSTOM design to a panel, watch SSE preview, confirm a 3-frame
  design plays as an animation and a 1-frame design publishes a static retained frame.

## 10. Contract checklist (the #1 bug source)

- [ ] `screenType` string `CUSTOM` identical in: frontend factory `as const`, `ScreenType` enum, `@JsonSubTypes` name.
- [ ] `CustomScreenConfig` JSON keys: `screenType` + `durationSeconds` + **exactly one of** `customScreenId` (`long`) / `design` (`string`) — no extras (strict Jackson; the delegating creator tolerates either shape).
- [ ] Library reference contract (§4.1): `customScreenId` ↔ `px75_custom_screen.custom_screen_id`; render-time hydration, save-time `validateRotation`, dangling → skip.
- [ ] pxd schema §3 identical in `src/types/pxd.ts` and `PxdDesign.java` (fonts §3.3 included;
      v2 parametric layers + §3.4.7 command-compilability rules included).
- [ ] Preview endpoint path/body/response exactly §5.9 (parametric designs: §3.6 sampled frames,
      same shape).
- [ ] 64×32 everywhere; server quantizes to RGB565 on publish (`ImageService`).
