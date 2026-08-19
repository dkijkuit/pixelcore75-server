# AGENTS.md

Spring Boot 3.5 server (Java 21, Gradle 9.3) that drives PixelCore75 LED-matrix panels: renders screens (crypto/CoinGecko, soccer/ESPN, F1, weather, clock, images, animations) to RGB565 frames and publishes them over MQTT (topic = panel serial). Single module, no CI, no formatter/linter.

## Commands

```bash
./gradlew bootRun                                  # run app (from repo root, see gotchas)
./gradlew test                                     # full suite
./gradlew test --tests 'CryptoTickerServerApplicationTests'   # single class
./gradlew build -x test                            # build without tests
```

- Java 21 toolchain is auto-provisioned via the foojay resolver; no local JDK 21 needed.

## Tests require running infra

- `CryptoTickerServerApplicationTests` is a `@SpringBootTest` with no test profile or test resources. It loads the real `application.yml`, so it needs Postgres **and** an MQTT broker on localhost — start them first: `docker compose up -d` (Postgres 17 + NanoMQ on 1883 + mqttx-web on 8090).
- The MQTT broker URL is hardcoded in `MqttConfig.java` (`tcp://localhost:1883`), not configurable via `application.yml`.
- `src/test/.../Color565Utils.java` is a manual debug utility, not a real test suite. Some of its cases read `.ttf` files from the repo root that don't exist in the repo, so those cases fail if invoked. Don't treat that as a regression.

## Architecture / repo facts

- Naming mismatch: repo/product is "pixelcore75", but the Java root package is `nl.ctasoftware.crypto.ticker.server` (historical "crypto-ticker-server" name). Put new code in the existing package tree.
- Layers: `controller/` (REST), `service/` (with `screen/` per-screen renderers, `job/` for the scheduled publish loop, `panel/`, `user/`), `repository/`, `model/` (+ `model/dto`, `model/panel/config`), `security/` (JWT access/refresh + ticket auth for SSE).
- Adding a screen type requires touching: `ScreenType`, a config record (sealed — update `ScreenConfig` permits + `@JsonSubTypes`), a `ScreenService` bean, and the exhaustive switch in `PanelScreenJob.renderScreen`. Jackson is strict, so REST/DB JSON must carry every record component.
- ANIMATION screens: `PanelScreenJob` uploads frames per the binary protocol implemented in the sibling `../pixelcore75-firmware` repo (`src/main.cpp`); the panel loops animations from LittleFS itself. The magic bytes (`ANIM`/`ANIF`/`ANIP`), payload layouts (14-byte start = magic + frameCount u16 + delayMs u16 + uploadId u32 + flags u8 + slot u8; 4100-byte frames; 9-byte `ANIP` play and `ANIL` ack = magic + slot u8 + echoed uploadId u32, all LE), frame-count limits (2–200), and ≥10 ms delay clamp must match the firmware exactly — changing one side breaks the other. Upload model (stage-ahead): during every screen slot the server immediately uploads the *next* screen's animation (stage-only flag) at full speed — no inter-frame pacing — and the panel stores it in one of 32 slot files (`/a0..31.bin`, LittleFS ~4.9MB via the project partition table; a slot = the screen's ordinal among the rotation's animations mod 32) until the boundary, which only sends `/anim/play`. Slots are temporary scratch, not a persistent cache: every animation is re-uploaded fresh each cycle, and there is no content-hash skip. If the next animation's slot is the one currently playing (single-animation rotation, or >32 animations wrapping), staging is skipped and that boundary uploads inline instead (the firmware stops the playing animation when the replacement lands so the slot-file rename succeeds). Staging time is subtracted from the current slot (1 s floor) so boundaries stay on schedule; if staging overruns the slot, the previous screen simply holds until it completes (rotation timing drifts). Inline upload is also the fallback at a boundary with nothing staged (server restart, failed staging, rotation starting on an animation) or after a play-ack timeout (2 s, e.g. panel reflashed with wiped FS — the panel evicts idle slot files for space, which is fine since nothing persists). Stutter-free concurrency is a firmware property: the MQTT callback only copies frames into an 8×4 KB RAM ring; `loop()` drains it to flash one 256 B page per pass while an animation plays (a full frame per pass when idle), and gates `client.loop()` when the ring is nearly full so TCP/MQTT backpressure throttles the server; the animation tick advances by exactly one delay per frame (catch-up) so a flash-erase stall holds one frame instead of permanently slowing playback. A static frame stops playback but keeps the slot files. Retained base-topic image lifecycle: static screens publish it retained (so a panel booting during a static slot shows the current screen), and every confirmed animation play clears it with an empty retained publish — a booting panel then gets no base frame and auto-resumes its slot animation; without the clear, a stale retained frame (e.g. `no_config_found.png` from the pre-config era, never replaced by animation-only rotations) would arrive at boot and stop the auto-resumed animation. `AnimationLoadAckService` holds each animation slot until the `ANIL` ack with the matching uploadId (upload: 5 s + 250 ms/frame, max 60 s; play: 2 s) so the display duration counts from playback start, not publish time.
- Screen images/fonts are loaded via relative paths (`assets/fonts/*.ttf`, `assets/images/*.png`) — the app must run with the repo root as working directory (`bootRun` does; running the built jar from elsewhere breaks font/image loading).
- `generated_images/` is a gitignored scratch dir for rendered output; `assets/` is committed runtime data.
- Persistence: Hibernate `ddl-auto: update` manages the schema. The `spring.flyway` block in `application.yml` is inert — Flyway is not on the classpath and there is no `db/migration`.
- Jackson is configured strict (`fail-on-unknown-properties`, `fail-on-missing-creator-properties`, `fail-on-null-creator-properties` all true): DTOs must match the panel/client JSON exactly or deserialization throws.
- Concurrency is custom: virtual-thread executors in `SchedulerConfiguration` (SSE heartbeats, panel screen jobs). Don't assume `spring.threads.virtual.enabled`.
- SSE live preview: tickets (`SseTicketService`) are validated but never consumed — EventSource auto-reconnect replays the original ticket URL, so single-use tickets turn any transient drop into a permanently stuck preview (1 h TTL bounds replay instead). `SseEmitter.send` is not thread-safe: all sends go through `sendGuarded` in `ImageBroadcasterService` (per-emitter lock), and every send failure (not just `IOException`) unregisters the emitter — an uncaught exception silently cancels the periodic heartbeat task.

## Docker

- Image build (see `BUILD.md`): `docker build -t pixelcore75-server .`
- `compose.build.yaml` is an override file with no top-level `services:` key — use it together with the base file: `docker compose -f compose.yaml -f compose.build.yaml up`. It will not parse on its own.

## Config

- DB credentials in `application.yml` match `compose.yaml` (user/db `pixelcore75`); no `.env` needed for local dev.
- `CRYPTO_API_KEY` / `SOCCER_API_KEY` env vars are optional (CoinGecko/ESPN clients work without them).
- JWT secrets have hardcoded dev defaults in `application.yml`.
