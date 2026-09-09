# Repository Optimization Plan

Date: 2026-09-06
Scope: performance, maintainability, traceability — with a deep dive on the scheduling/job system.
Decisions made with the owner: **replace the custom scheduler with JobRunr**, **replace Paho v3 with the HiveMQ MQTT client** (wire protocol to firmware unchanged), **Actuator health only** (no metrics backend yet), **adopt Flyway** on the live DB, **multi-instance later** (choose cluster-safe options now).

All file:line references verified against the tree at commit `41d223c`.

---

## 0. Current-state findings (evidence-backed)

### 0.1 Scheduling/job system
- `service/job/JobSchedulerService.java` is a hand-rolled scheduler (ThreadPoolTaskScheduler pool 8 + virtual-thread worker). Known issues:
  - `schedule()` sets `state.future` *after* `scheduler.schedule(...)` (lines 92–96): a concurrent `stop(force)` can miss the future; correctness survives only via the `cancelled` re-check, and `workerFuture.set` needs a manual re-cancel (lines 128–129).
  - `stopAll()` (53–57) shuts down beans Spring also manages (`destroyMethod="close"` on `jobWorker`, `ThreadPoolTaskScheduler` lifecycle) — double shutdown.
  - Hardcoded 30 s error retry (line 123); `catch (Throwable)` retries even `OutOfMemoryError`.
  - Wiring is by constructor-parameter *name*, not `@Qualifier` (`JobSchedulerService` ctor line 27 resolves one of two `ExecutorService` beans) — fragile.
- `service/job/PanelScreenJob.java` (1036 lines) is a god class: rotation index, MQTT wire encoding, ANIM staging/downgrade/fallback state machine, SSE preview streaming, ACMD publishing, synchronous disk I/O (`generated_images/<serial>.png` written on the rotation thread, lines 404–405, 483–484, 619–620, 982).
- Dead code: `PanelScreenJob.onContextClosed` (160–163) never fires (not a Spring bean); `SchedulerConfiguration.jobWorker` defines an unused `ThreadFactory` (44–48); `ImagePushConfig` defines an injected-nowhere `sseTaskExecutor` bean; the redundant second `@EnableScheduling` (`ImagePushConfig:10`).
- Job identity is the panel **serial** (`PanelScreenJob.id` = serial, line 157); `Px75PanelService.deletePanel` stops by serial (line 67) before deleting rows. `PATCH /v1/panel` can change the serial **without rescheduling** — the running job keeps the old id (latent bug, fixed by this plan).
- Per-job mutable state that survives across runs: rotation index `screenIndex` (95, 149) and one-ahead staging `stagedNextIdx/stagedNextUploadId` (90–91). A per-execution job model (JobRunr) requires externalizing both.
- Test coverage: only `JobSchedulerServiceTests` tests the scheduler itself; all `PanelScreenJob*Tests` bypass it and call `run()` directly (good — the swap is insulated). Semantics pinned by `JobSchedulerServiceTests`: same-id replacement cancels the old job, `stop(force)` interrupts in-flight runs.

### 0.2 MQTT
- 9 publish sites, all in `PanelScreenJob`; 1 subscribe in `AnimationLoadAckService` (constructor-time, `+/anim/loaded`). QoS 1 everywhere except `<serial>/cmd` (QoS 0); retained only on the base topic (3 empty-payload clears + 1 image publish via the only `MqttMessage` overload, line 977–979).
- `MqttConfig` hardcodes `tcp://localhost:1883` (line 19) and **connects synchronously in the bean** (line 25) → broker-down = boot failure; `automaticReconnect` only helps after the first successful connect.
- `cleanSession(false)` keeps the ack subscription alive across broker reconnects today; HiveMQ requires an explicit resubscribe-on-(re)connected callback or acks silently stop after the first drop.
- Paho sync `publish` blocks per frame until the broker ACK — the "full speed" frame loop is RTT-serialized. The plan's async swap must re-introduce *bounded* backpressure (the firmware relies on MQTT flow control to pace flash writes; see AGENTS.md protocol section).
- Checked `MqttException` is threaded through signatures/catches at PanelScreenJob 411/491/543/631/681/903/943, MqttConfig, AnimationLoadAckService — all simplify (HiveMQ is unchecked) but every catch site must be revisited.
- `spring-integration-mqtt` (build.gradle:23) exists solely for `DefaultMqttPahoClientFactory.getClientInstance(...)`.

### 0.3 Performance
- 7 `RestClient` beans without timeouts (ClientConfig 27–85: coingecko, open-meteo, espn×2, sofascore, jolpi, sportsdb) + `SpotifyAlbumArtClient.java:63` (`RestClient.create()`, no timeouts at all). One hung upstream stalls a rotation slot indefinitely (virtual thread, but the slot timing drifts).
- SSE preview at 10 Hz (`COMMAND_PREVIEW_TICK_MS`) submits one task per emitter per tick on an **unbounded** virtual-thread executor; slow viewers accumulate unbounded work. Needs latest-wins coalescing per emitter.
- `ImageService.bufferedImageToBytes` calls `getRGB(x,y)` per pixel (2048/frame × up to 60 frames per staging upload); bulk raster fetch is a free win.
- Per-serial in-memory maps leak on panel delete: `previewGenerations` (Px75PanelJobScheduler:57, no `.remove` anywhere) and `latestPngByPanel` (ImageBroadcasterService:27, no removal). `emittersByPanel` self-cleans.
- `AnimationLoadAckService` clears `ackedUploadIds`/`v2DowngradeDeadlines` wholesale on overflow (4096-entry bound) — Caffeine does this properly with per-entry expiry.
- Startup N+1: one `getPanelConfig` per panel, sequentially (`Px75PanelJobScheduler.scheduleStartup`).

### 0.4 Traceability
- No Actuator, no Micrometer, no health endpoints, no metrics. Domain outcomes (staged / inline / hash-cached / raw-fallback / command-page-flip) exist only as log lines.
- MDC `jobId`/`job` set by `JobSchedulerService` (103–104) is a good baseline; no `serial`/`panelId` MDC on render/preview threads.

### 0.5 Maintenance
- `spring.flyway` block in application.yml is inert (Flyway not on classpath, no `db/` dir); schema is Hibernate `ddl-auto: update` — fragile and untraceable. 5 entities (`px75_users` + derived `px75_roles` collection table, `px75_panel`, `px75_panel_config` with **jsonb**, `px75_custom_screen`, `px75_spotify_connection`); all IDENTITY PKs except `px75_panel_config.panel_id`.
- `javax.xml.bind:jaxb-api:2.3.0` (build.gradle:35): **zero imports anywhere** — dead dependency.
- `net.datafaker:datafaker` (build.gradle:37): only referenced by an import (line 5) and a fully commented-out seeding block (`CryptoTickerServerApplication:45–77`) — dead on the prod path.
- JJWT 0.11.5 → 0.12.x is a one-file migration (`security/JwtService.java`, ~6 mechanical API renames; see §6).
- `contextLoads` (`CryptoTickerServerApplicationTests`) boots the full context and needs live Postgres + MQTT (MqttConfig:25 connects at bean creation); no `src/test/resources` exists.

---

## 1. Target architecture

```
JobRunr 8.8.2 (Postgres-backed, dashboard :8000)      HiveMQ MQTT client 1.4.0 (MQTT 3.1.1)
  └─ PanelRotationJob.execute(serial)                    ├─ /<serial>        retained frame / clear
       ├─ RotationPlanner     (DB config → next slot)    ├─ /<serial>/cmd    ACMD batches (QoS 0)
       ├─ AnimationTransport  (stage/inline/ANIP/RLE)    ├─ /<serial>/anim/* start/frame/play
       ├─ CommandPublisher    (batch/refresh streams)    └─ /<serial>/anim/loaded  (subscribe, ANIL)
       ├─ PreviewStreamer     (SSE, latest-wins)      MqttTransport interface (fake in tests)
       └─ RotationStateService (per-serial transient state + epoch)
Flyway (Boot-managed 11.7.2) · Actuator health · Testcontainers
```

Per-serial **epoch** (AtomicLong in `RotationStateService`) unifies the current `previewGenerations` map and the force-stop interrupt semantics: config-save bumps the epoch and enqueues an immediate job; any in-flight or pending stale run exits at the next safe point (epoch check before every publish — `publishCommandBatch` already holds the serial's generation monitor; extend it to all publishes).

---

## 2. Library versions (verified 2026-09-06 against Maven Central)

| Library | Artifact | Version | Note |
|---|---|---|---|
| JobRunr | `org.jobrunr:jobrunr-spring-boot-3-starter` | 8.8.2 | artifact is hyphenated `spring-boot-3`; 9.0.0 is beta — don't use |
| HiveMQ | `com.hivemq:hivemq-mqtt-client` | 1.4.0 | replaces Paho + spring-integration-mqtt |
| JJWT | `io.jsonwebtoken:jjwt-{api,impl,jackson}` | 0.12.7 | one-file migration |
| Flyway | `flyway-core` + `flyway-database-postgresql` | Boot BOM pins 11.7.2 | no explicit version |
| Actuator | `spring-boot-starter-actuator` | Boot-managed | |

JobRunr config facts (verified from `JobRunrProperties` source, not docs): `jobrunr.background-job-server.enabled` defaults to **false** in the starter; `jobrunr.dashboard.enabled` defaults to **false**; poll interval property is `jobrunr.background-job-server.poll-interval-in-seconds` (default 15 — must set **1** for second-scale rotation delays).

---

## 3. Phase 3 (first): Flyway on the live DB

*Why first: JobRunr adds its own tables via its installer; do the schema story change before anything else writes DDL.*

1. Add `org.flywaydb:flyway-core` + `org.flywaydb:flyway-database-postgresql` (no version — Boot BOM).
2. Generate `V1__baseline.sql` from the **live** schema (`pg_dump --schema-only`), not from Hibernate models — then diff against the 5 entities to catch drift (remember `px75_roles`; `jsonb` on `px75_panel_config.screens_config`; `text` columns; plain-bigint PK on `px75_panel_config`).
3. Configure: `spring.flyway.baseline-on-migrate: true`, `baseline-version: 0` (baseline applies V1 to the existing DB), remove the trap by making the existing inert block real.
4. `spring.jpa.hibernate.ddl-auto: update` → `validate`.
5. Acceptance: fresh Postgres (`docker compose down -v && up`) and existing-DB boot both converge to the same schema; `./gradlew test` unchanged.

## 4. Phase 1: JobRunr replaces the custom scheduler

**Delete:** `JobSchedulerService`, `ReschedulableJob`, `jobScheduler`/`jobWorker` beans, the `PanelJobScheduler` interface impls' internals, `JobSchedulerServiceTests` (semantics move to JobRunr + new integration tests). Keep `sseExecutor`/`sseScheduler` and `SseTicketService`'s `@Scheduled` (unrelated); delete dead `ImagePushConfig` after confirming nothing injects `sseTaskExecutor`.

**Add:** `org.jobrunr:jobrunr-spring-boot-3-starter:8.8.2`, config:
```yaml
jobrunr:
  background-job-server:
    enabled: true
    poll-interval-in-seconds: 1        # rotation delays are 5–60 s; default 15 is unusable
    worker-count: 8
  database:
    type: sql
  dashboard:
    enabled: true                      # port 8000 — the traceability win
```

**New structure** (`service/job/`):
- `PanelRotationJob` — JobRunr job bean, `void execute(String serial)`: load panel + config **from DB each run** (replaces the constructor-snapshot config; config saves become visible next cycle automatically); plan slot via `RotationPlanner`; render via the extracted components; then `jobScheduler.schedule(() -> panelRotationJob.execute(serial), Instant.now().plusMillis(slotMillis))` — the successor is persisted, so restarts self-heal.
- `RotationStateService` — per-serial in-memory state: rotation index (externalized `screenIndex`), staged-next (`stagedNextIdx/uploadId`), epoch counter, pending-successor job id. Job parameters are **only** the serial (never entities — JobRunr serializes them).
- `RotationPlanner`, `AnimationTransport`, `CommandPublisher`, `PreviewStreamer` — extracted from `PanelScreenJob` (see Phase 5 for the split boundaries).

**Semantics mapping (current → new):**
| Current | New |
|---|---|
| `schedulePanelScreenJob(panelId, userId)` (config-save/register; force-stop + immediate run) | epoch bump + `jobScheduler.delete(pendingSuccessor)` + enqueue immediate run |
| `deletePanel` stops by serial before DB delete (`Px75PanelService:67`) | delete pending successor job for serial *before* row deletion (preserve the ordering rationale), then `RotationStateService.evict(serial)` |
| 0–250 ms reschedule jitter | keep (add inside the successor delay) |
| 30 s error retry | JobRunr failure retries with exponential backoff (`@Job(retries=3)`); after exhaust, failure visible in dashboard instead of silent loop |
| interrupt in-flight run | no interrupts; epoch checked at safe points (pre-publish, pre-stage, post-await); `awaitLoaded` waits stay bounded (≤60 s) so a stale run dies naturally |
| `previewGenerations` map | replaced by the per-serial epoch |
| startup stagger 250 ms × index | no stagger needed (DB-claimed), but keep a tiny jitter to spread broker connections |
| `PATCH /v1/panel` serial change doesn't reschedule (latent bug) | job keyed by serial: update path bumps epoch for old serial + enqueues for new |

**Startup reconciliation:** on `ApplicationReadyEvent`, for every panel in DB: if JobRunr has no scheduled/enqueued job for that serial → enqueue immediate. Handles crash-between-run-and-successor.

**Config:**
- `PanelController` injects the JobRunr `JobScheduler` (or a thin `PanelRotationControl` facade) instead of `Px75PanelJobScheduler`; delete `scheduleStartup`'s N+1 loop (replaced by reconciliation).
- Add `serial`/`panelId` to MDC via a JobRunr `JobFilter` (replaces the old MDC wiring).

**Test impact (exact files):** `PanelScreenJobDisabledTests` (ctor 131–134), `PanelScreenJobHashSkipTests` (224–225), `PanelScreenJobCustomHydrationTests` (152–154, 10-arg), `PanelScreenJobCommandTests` (480–481, 490–491) — all call `run()` directly and migrate to `PanelRotationJob` with the same seams (mocked transport/planner). New: `PanelRotationJobJobRunrTests` — an integration test against `jobrunr` in-memory storage (`MemoryStorageProvider` + `BackgroundJobServer` in-process) covering: successor scheduling with computed delay, epoch-stale no-op, delete-before-DB-delete ordering, startup reconciliation idempotence. `Px75PanelServiceTests` (42, 57) update `stop(...)` verification to the new facade.

**Acceptance:** all parity/protocol tests green; JobRunr dashboard shows one job per panel with success/failure history; config-save swaps content within one slot; panel delete leaves zero pending jobs; kill -9 mid-slot + restart resumes rotation ≤ poll interval.

## 5. Phase 2: HiveMQ MQTT client

**Delete:** Paho dep (build.gradle:31), `spring-integration-mqtt` (build.gradle:23), `MqttConfig`.

**Add:** `com.hivemq:hivemq-mqtt-client:1.4.0`.

**New `MqttConfig`:** async `MqttClient` (Rx/	callback style), broker from new property `pixelcore75.mqtt.broker-url` (default `tcp://localhost:1883` — fixes the hardcoded URL), client id `pixelcore75-server-<uuid8>`, `automaticReconnectWithDefaultConfig`, `cleanStart(false)` + persistent session. **Do not connect synchronously in the bean**: use `connectWith().applyConnectOptions()` async and a startup `ApplicationReadyEvent` that awaits connect with a bounded timeout, logging (not crashing) if the broker is down — auto-reconnect picks it up. Decide-and-document the boot-failure semantics change (today: fail fast).

**`MqttTransport` interface** (`service/mqtt/`): `publish(topic, bytes, qos, retained)`, `publishAsync(...)` returning a future, `subscribe(filter, (topic, payload) -> ...)`. Production impl wraps HiveMQ; tests use the existing fakes through it. This keeps all docker-free protocol tests (HashSkip/Command/AnimationLoadAck) docker-free.

**Publish-site mapping (all 9, PanelScreenJob):**
| Site | Mapping |
|---|---|
| `/anim/start`, `/anim/frame` ×N, `/anim/play` (QoS 1, lines 336/357/911/917) | `publishAsync`; **bounded in-flight window** (semaphore, ~8–16 outstanding) re-introduces the backpressure the firmware's RAM-ring flow control expects — Paho's per-message blocking was accidental pacing; unbounded async would risk overflowing panel RAM |
| base-topic retained clear ×3 (396/473/606) | QoS 1 retained empty payload |
| static image retained (977–979, the only `MqttMessage` site) | `publish(serial, bytes, AT_LEAST_ONCE, true)` |
| `/cmd` QoS 0 (650, under the generation monitor) | QoS 0 async — no window needed |

**Subscribe mapping:** `AnimationLoadAckService` ctor → `MqttTransport.subscribe("+/anim/loaded", ...)` (identical payload contract: 9-byte ANIL, slot u8, uploadId u32 LE). **Required**: register a `connected` (and `reconnected`) callback that re-subscribes — HiveMQ does not restore subscriptions unless the broker session does; verify against NanoMQ behavior with `cleanStart(false)` and add the callback regardless (belt and braces).

**Exception surface:** unchecked — remove `throws MqttException`/multi-catches at the listed sites (§0.2); keep the logged-and-continue behavior at refresh/page-flip failure sites (543/681 semantics preserved).

**Test impact:** 5 test files rework from Paho mocks to the transport fake (imports at §0.2-inventory: DisabledTests 11–12/127, HashSkipTests 11–13, CustomHydrationTests 12–13, AnimationLoadAckServiceTests 3–5, CommandTests 23–25). `AnimationLoadAckServiceTests` keeps driving the listener directly.

**Also in this phase:** swap `AnimationLoadAckService`'s wholesale-cleared maps for Caffeine caches (`ackedUploadIds`: expireAfterWrite ~2 h, maximumSize 4096; `v2DowngradeDeadlines`: expireAfter = remaining window) — removes the clear-wholesale-on-overflow behavior.

**Acceptance:** protocol tests green; on-bench: animation upload to real panel plays within expected time (verify the in-flight window doesn't overshoot firmware RAM-ring limits); broker restart mid-animation re-subscribes and acks resume.

## 6. Phase 4: Actuator health

1. Add `spring-boot-starter-actuator`; expose `health,info` only (`management.endpoints.web.exposure.include: health,info`).
2. Custom `MqttHealthIndicator`: connected-client state from the HiveMQ client.
3. DataSource health is built-in. No Prometheus/metrics yet (per decision) — the Actuator wiring does not block adding Micrometer later.

## 7. Phase 5: render-pipeline split + performance

**Split `PanelScreenJob`** (can land before or after Phase 1; Phase 1 already renames it `PanelRotationJob` — do the split as part of Phase 1 to avoid touching the same file twice):
- `RotationPlanner`: hydration (`hydratedScreens`, 1009–1035), disabled-screen filtering, slot index math (`getScreenIndex`, `animationSlotFor`).
- `AnimationTransport`: `stageNextAnimation`, `consumeStaged`, `sendAnimationToPanel`, payload builders (`animStartPayload`/`animFramePayload`/`animPlayPayload`), `UploadIdHasher` wiring, codec/downgrade logic. The `ANIM_*` constants move here (`UploadIdHasher.java:37` + tests reference them — update imports).
- `CommandPublisher`: command-path rendering incl. `renderRefreshingCommandScreen`, `refreshCommandBatches`, `cycleCommandPages`, `publishCommandBatch` (generation/epoch monitor).
- `PreviewStreamer`: all `stream*Preview` variants + `scaleImageAndPublish`.
- `PanelRotationJob` orchestrates only.

**Performance items:**
1. Shared timed `JdkClientHttpRequestFactory` (connect 3 s / read 5 s, as `timedJdkRequestFactory()` at ClientConfig:149) applied to the 7 bare beans + `SpotifyAlbumArtClient` (replace `RestClient.create()` at line 63 with an injected builder; reuse one factory instance, not 8).
2. SSE latest-wins coalescing in `ImageBroadcasterService`: per-emitter `AtomicBoolean pending` — if a send is queued, only swap the payload ref; slow viewers always receive the newest frame, never queue unbounded work. Keep `sendGuarded` locking.
3. `ImageService.bufferedImageToBytes`: one `img.getRGB(0,0,W,H,pixels,0,W)` bulk fetch (or `Raster`) before the loop; keep byte-exact output (parity tests pin it).
4. `generated_images/*.png` writes: async on a virtual thread + content dedup (skip write when bytes unchanged); never on the publish path.
5. Delete-path leak fix: `RotationStateService.evict(serial)` + `imageBroadcasterService.removeSerial(serial)` (new) called from `Px75PanelService.deletePanel` (line ~67) — purges `latestPngByPanel` (and the epoch map).
6. (Optional, needs frontend coordination — defer): preview as 64×32 PNG with client-side pixelated scaling instead of 10 Hz 640×320 PNG+Base64 re-encode per panel.

## 8. Phase 6: dependency/maintenance cleanup

1. **Remove** `javax.xml.bind:jaxb-api` (build.gradle:35) — zero usages.
2. **Remove** `net.datafaker` (build.gradle:37) + dead import and commented block (`CryptoTickerServerApplication:5, 45–77`).
3. **JJWT 0.11.5 → 0.12.7** (`build.gradle:27–29`), single-file migration in `security/JwtService.java`:
   - builder: `setIssuer/setSubject/setId/setIssuedAt/setExpiration` → `issuer/subject/id/issuedAt/expiration`
   - `signWith(key, SignatureAlgorithm.HS256)` → `signWith(key, Jwts.SIG.HS256)`
   - `Jwts.parserBuilder()` → `Jwts.parser()`; `setAllowedClockSkewSeconds` → `clockSkewSeconds`; `setSigningKey` → `verifyWith`; `parseClaimsJws().getBody()` → `parseSignedClaims().getPayload()`.
4. **Testcontainers**: add `spring-boot-testcontainers` + `postgres` module; `CryptoTickerServerApplicationTests` becomes hermetic with a `@DynamicPropertySource` datasource (no MQTT needed once the transport is faked / broker URL configurable — Phase 2 unblocks this). Keep the AGENTS.md "tests need docker compose" note updated, or delete it if no test needs live infra.
5. AGENTS.md updates: MQTT broker URL configurability, JobRunr dashboard port, Flyway baseline, new `service/job` layout.

## 9. Ordering & risk

```
Phase 3 (Flyway)  →  Phase 1 (JobRunr + PanelScreenJob split)  →  Phase 2 (HiveMQ)
                                                          ↘ Phase 4 (Actuator)
                                                            Phase 5 remainder (perf)  →  Phase 6 (cleanup)
```
- Phases 1 and 2 are independent; both go through the `MqttTransport`/component seams, so sequence them 1 → 2 to keep review size sane.
- **Risks:** (a) JobRunr 1 s poll adds ≤1 s slot-boundary jitter (acceptable for an LED wall; document it); (b) HiveMQ resubscribe-on-reconnect must be tested against NanoMQ specifically; (c) the async-publish in-flight window must be bench-tested so flash pacing matches firmware expectations (start at 8 outstanding); (d) Flyway baseline must come from `pg_dump` of the live DB, not Hibernate assumptions; (e) interrupt-based replacement semantics become epoch-check-based — verify no publish path skips the epoch check.

## 10. Verification checklist (after each phase)

- `./gradlew test` (with docker compose up for the SpringBootTest until Phase 6 replaces it).
- Golden-image parity suites must stay byte-identical (`*CommandParityTests`, `CustomScreenCommandTests`).
- `./gradlew build -x test` compiles clean; boot smoke test against `docker compose up -d` (Postgres + NanoMQ).
- On-bench: one full rotation cycle per phase on a real panel (animation staging + play ack path).
