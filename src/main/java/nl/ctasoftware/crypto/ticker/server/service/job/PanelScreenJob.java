package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.ScreenServiceNotFoundException;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.*;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.AircraftScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.animation.AnimationScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.clock.ClockScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.CryptoScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenResolver;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.date.DateScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.image.ImageScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatchService;
import nl.ctasoftware.crypto.ticker.server.service.screen.spotify.SpotifyScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.WeatherScreenService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class PanelScreenJob implements ReschedulableJob {
    static final String ANIM_START_TOPIC = "/anim/start";
    static final String ANIM_FRAME_TOPIC = "/anim/frame";
    static final String ANIM_PLAY_TOPIC = "/anim/play";
    static final String CMD_TOPIC = "/cmd";
    static final byte ANIM_FLAG_STAGE_ONLY = 0x01;
    static final int ANIM_CODEC_MASK = 0x06;
    static final int ANIM_CODEC_RAW = 0x00;
    static final int ANIM_CODEC_PAL_RLE = 0x02;

    /** SSE preview tick for command screens (ms): smooth for the radar sweep speeds, cheap otherwise. */
    static final long COMMAND_PREVIEW_TICK_MS = 100;

    /**
     * Animation slots on the panel (must match firmware ANIM_MAX_SLOTS). Slot files double as a
     * persistent content cache keyed by uploadId (a content hash from {@link UploadIdHasher}):
     * the panel persists the id per slot, so when the next cycle's content hashes to the last
     * acked id the upload is skipped entirely and the boundary sends only {@code /anim/play}
     * (capacity is shared ~4.9MB LittleFS; the panel evicts idle slot files for space when
     * needed, invalidating their persisted ids).
     */
    static final int MAX_ANIM_SLOTS = 32;

    /** Play commit on the panel is a rename + first-frame draw (ms); cap before inline fallback. */
    static final Duration ANIM_PLAY_ACK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Floor for a slot shortened because the next animation was staged during it. Normally the
     * staging fits inside the slot and the boundary stays on schedule; if it overruns, the
     * previous screen simply holds until staging completes (the slot never renders half done).
     */
    static final long MIN_ANIMATION_SLOT_MILLIS = 1000;

    /**
     * Upload staged for the next rotation screen (if it is an animation): its boundary only
     * sends {@code /anim/play}. Only ever one entry ahead — a job replacement (config save)
     * drops it and that boundary falls back to an inline upload. Also set for a "hash-cached"
     * staging (upload skipped because the panel slot already holds the exact content), in
     * which case the play-time ack confirms it or the boundary re-uploads inline.
     */
    private long stagedNextUploadId;
    private int stagedNextIdx = -1;

    final Px75Panel px75Panel;
    final Px75PanelConfig panelConfig;
    final AtomicInteger screenIndex;
    final ImageService imageService;
    final IMqttClient mqttClient;
    final ImageBroadcasterService imageBroadcasterService;
    final AnimationLoadAckService animationLoadAckService;

    /**
     * Resolves CUSTOM library references ({@code customScreenId}) to their current design
     * each cycle, so library edits propagate live; dangling references are skipped.
     * Identity in the legacy constructor (configs that never carry references).
     */
    final CustomScreenResolver customScreenResolver;

    /**
     * {@code pixelcore75.command-encoding.enabled} (default false): screens whose service
     * implements {@link CommandScreenService} render as ACMD batches on {@code <serial>/cmd}
     * instead of frames/static images. Opt-in for the mixed fleet: old firmware never
     * subscribes to {@code /cmd}, so emission must stay off until a panel runs ACMD firmware.
     */
    final boolean commandEncodingEnabled;

    private final AtomicBoolean running = new AtomicBoolean(true);

    @Getter
    final String id;

    private final Map<ScreenType, ScreenService<? extends ScreenConfig>> screenServices;

    /** Per-serial render generations, shared across job instances, to supersede stale preview streams. */
    private final ConcurrentMap<String, AtomicInteger> previewGenerations;

    public PanelScreenJob(final Px75Panel px75Panel, final Px75PanelConfig panelConfig,
                          final List<ScreenService<? extends ScreenConfig>> screenServices,
                          final ImageService imageService, final IMqttClient mqttClient,
                          final ImageBroadcasterService imageBroadcasterService,
                          final AnimationLoadAckService animationLoadAckService,
                          final ConcurrentMap<String, AtomicInteger> previewGenerations,
                          final boolean commandEncodingEnabled) {
        this(px75Panel, panelConfig, screenServices, imageService, mqttClient, imageBroadcasterService,
                animationLoadAckService, previewGenerations, commandEncodingEnabled, config -> config);
    }

    public PanelScreenJob(final Px75Panel px75Panel, final Px75PanelConfig panelConfig,
                          final List<ScreenService<? extends ScreenConfig>> screenServices,
                          final ImageService imageService, final IMqttClient mqttClient,
                          final ImageBroadcasterService imageBroadcasterService,
                          final AnimationLoadAckService animationLoadAckService,
                          final ConcurrentMap<String, AtomicInteger> previewGenerations,
                          final boolean commandEncodingEnabled,
                          final CustomScreenResolver customScreenResolver) {
        this.px75Panel = px75Panel;
        this.panelConfig = panelConfig;
        this.imageService = imageService;
        this.mqttClient = mqttClient;
        this.screenIndex = new AtomicInteger(-1);
        this.screenServices = screenServices.stream()
                .collect(Collectors.toUnmodifiableMap(ScreenService::getScreenType, Function.identity()));
        this.imageBroadcasterService = imageBroadcasterService;
        this.animationLoadAckService = animationLoadAckService;
        this.previewGenerations = previewGenerations;
        this.commandEncodingEnabled = commandEncodingEnabled;
        this.customScreenResolver = customScreenResolver;
        this.id = px75Panel.getSerial();
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent e) {
        running.set(false);
    }

    @Override
    public Optional<Duration> run() {

        final List<? extends ScreenConfig> rawConfig = panelConfig.getScreensConfig();

        if (rawConfig == null || rawConfig.isEmpty()) {
            log.warn("----> No screen configs found, not scheduling screen jobs for panel: {}", panelConfig.getPanelId());
            final BufferedImage missingConfigImage = imageService.imageToBufferedImage("assets/images/no_config_found.png");
            try {
                sendImageToPanel(missingConfigImage);
                scaleImageAndPublish(missingConfigImage);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return Optional.empty();
        }

        // Resolve CUSTOM library references to their current design each cycle (edits to a
        // library screen propagate to every panel using it). Dangling references are
        // dropped with a warning so one deleted screen cannot break the whole rotation.
        final List<? extends ScreenConfig> screensConfig = hydratedScreens(rawConfig);
        if (screensConfig.isEmpty()) {
            log.warn("----> All screens of panel {} reference missing custom screens; retrying in 30s",
                    panelConfig.getPanelId());
            try {
                final BufferedImage missingConfigImage =
                        imageService.imageToBufferedImage("assets/images/no_config_found.png");
                sendImageToPanel(missingConfigImage);
                scaleImageAndPublish(missingConfigImage);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return Optional.of(Duration.ofSeconds(30));
        }

        final List<? extends ScreenConfig> activeScreens =
                screensConfig.stream().filter(s -> !s.disabled()).toList();
        if (activeScreens.isEmpty()) {
            log.warn("----> All screens of panel {} are disabled, not scheduling screen jobs", panelConfig.getPanelId());
            final BufferedImage missingConfigImage =
                    imageService.imageToBufferedImage("assets/images/no_config_found.png");
            try {
                sendImageToPanel(missingConfigImage);
                scaleImageAndPublish(missingConfigImage);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            return Optional.empty();
        }

        if (!running.get()) {
            log.warn("Not re-scheduling screen jobs for panel, because it's ending: {}", panelConfig.getPanelId());
        }

        if (Thread.currentThread().isInterrupted()) {
            // Vervangen/gestopt door een config-save of paneel-delete: niet meer publiceren.
            log.debug("----> Job for panel {} interrupted before render, skipping", panelConfig.getPanelId());
            return Optional.empty();
        }

        final int previewGeneration = bumpPreviewGeneration();
        final int screenIdx = getScreenIndex(activeScreens);
        final ScreenConfig screenConfig = activeScreens.get(screenIdx);

        long durationMillis;
        try {
            log.debug("----> Next screen job for panel: {}", panelConfig.getPanelId());
            renderScreen(screenConfig, activeScreens, previewGeneration, screenIdx);
        } catch (Exception e) {
            log.error("Error rendering screen job for panel: {}", panelConfig.getPanelId(), e);
        }

        // While this screen displays, immediately push the next screen's animation to the
        // panel (it stores it and only plays it at the boundary). Staging time is subtracted
        // from the slot so the boundary stays on schedule; if staging overruns the slot the
        // previous screen simply holds until it completes (floor keeps a minimum display).
        // stageNextAnimation never throws: failures log and fall back to an inline upload.
        if (Thread.currentThread().isInterrupted()) {
            log.debug("----> Job for panel {} interrupted before staging, skipping", panelConfig.getPanelId());
            return Optional.empty();
        }
        final long stagedMillis = stageNextAnimation(screenIdx, activeScreens);
        durationMillis = Math.max(MIN_ANIMATION_SLOT_MILLIS,
                Duration.ofSeconds(screenConfig.durationSeconds()).toMillis() - stagedMillis);

        return Optional.of(Duration.ofMillis(durationMillis));

    }

    void renderScreen(final ScreenConfig screenConfig, final List<? extends ScreenConfig> screens,
                      final int previewGeneration, final int screenIdx) {
        final ScreenService<? extends ScreenConfig> screenService = screenServices.get(screenConfig.screenType());
        if (screenService == null) {
            throw new ScreenServiceNotFoundException(screenConfig.screenType());
        }

        log.debug("------> Rendering screen {} for panel {}", screenConfig.screenType(), panelConfig.getPanelId());

        // ACMD command path (plan §6): flag ON + the service implements it and declares this
        // config command-capable (CUSTOM: only parametric designs) → publish the first batch
        // to <serial>/cmd (multi-page screens cycle their further batches at each page dwell;
        // live screens refresh theirs on the refresh grid). Batch build failures fall back to
        // the screen's regular path (the flag is a fleet-wide toggle, a single screen must not
        // break a slot).
        if (commandEncodingEnabled && screenService instanceof CommandScreenService<?>
                && isCommandCapable(screenService, screenConfig)) {
            @SuppressWarnings("unchecked")
            final CommandScreenService<ScreenConfig> capableService =
                    (CommandScreenService<ScreenConfig>) screenService;
            final CommandScreenService.RefreshStream refresh =
                    buildCommandRefresh(capableService, screenConfig);
            if (refresh != null) {
                renderRefreshingCommandScreen(refresh, screenConfig, previewGeneration);
                return;
            }
            final CommandScreenService.BatchStream stream =
                    buildCommandBatches(capableService, screenConfig);
            if (stream != null) {
                renderCommandScreen(stream, screenConfig, previewGeneration);
                return;
            }
        }

        if (screenConfig instanceof FrameScreenConfig frameScreenConfig && frameScreenConfig.producesFrames()) {
            renderFrameScreen((FrameScreenService) screenService, frameScreenConfig, screens, previewGeneration, screenIdx);
            return;
        }

        final Optional<BufferedImage> screenImage = switch (screenConfig) {
            case WeatherScreenConfig w -> ((WeatherScreenService) screenService).renderScreen(w);
            case CryptoScreenConfig c -> ((CryptoScreenService) screenService).renderScreen(c);
            case ImageScreenConfig i -> ((ImageScreenService) screenService).renderScreen(i);
            case SoccerMatchScreenConfig i -> ((SoccerMatchService) screenService).renderScreen(i);
            case ClockScreenConfig i -> ((ClockScreenService) screenService).renderScreen(i);
            case DateScreenConfig i -> ((DateScreenService) screenService).renderScreen(i);
            case Formula1ScreenConfig i -> ((Formula1ScreenService) screenService).renderScreen(i);
            case AircraftScreenConfig a -> ((AircraftScreenService) screenService).renderScreen(a);
            case SpotifyScreenConfig s -> ((SpotifyScreenService) screenService).renderScreen(s);
            case AnimationScreenConfig a -> ((AnimationScreenService) screenService).renderScreen(a);
            case CustomScreenConfig c -> ((CustomScreenService) screenService).renderScreen(c);
        };

        screenImage.ifPresent(this::sendImageToPanel);
    }

    private <T extends FrameScreenConfig> void renderFrameScreen(final FrameScreenService<T> screenService,
                                                                 final T screenConfig,
                                                                 final List<? extends ScreenConfig> screens,
                                                                 final int previewGeneration,
                                                                 final int screenIdx) {
        final FrameScreenService.FrameStream stream = screenService.renderFrameStream(screenConfig);
        final List<BufferedImage> frames = stream.frames();
        final long frameDelayMs = stream.frameDelayMs();
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();
        final String serial = px75Panel.getSerial();
        final int slot = animationSlotFor(screenIdx, screens);
        final List<byte[]> payloads = framePayloads(frames);

        try {
            final long boundaryStartNanos = System.nanoTime();
            boolean playbackStarted = false;
            String mode = null;

            final Long staged = consumeStaged(screenIdx);
            if (staged != null) {
                // Uploaded to the panel during the previous screen (or still sitting in its
                // slot from an earlier cycle — hash-cached): committing is just a play (a
                // rename when freshly uploaded), no flash writes while anything displays.
                animationLoadAckService.arm(serial, staged);
                mqttClient.publish(serial + ANIM_PLAY_TOPIC, animPlayPayload(staged, slot), 1, false);
                playbackStarted = animationLoadAckService.awaitLoaded(serial, staged, ANIM_PLAY_ACK_TIMEOUT);
                mode = "staged";
                if (!playbackStarted) {
                    // The staged content is gone (panel evicted it for space, or was reflashed
                    // with wiped FS): upload it again right here at the boundary.
                    log.warn("------> Play ack timeout for panel {} slot {}; re-uploading inline", serial, slot);
                }
            }

            if (!playbackStarted) {
                // Nothing staged (server restart, failed staging, first cycle of a rotation
                // starting on an animation): if the panel's slot still holds this exact content
                // (its last acked id matches the content hash), commit is just a 9-byte play —
                // the panel stays silent when its persisted id or slot file doesn't match, and
                // the 2 s play-ack timeout below falls back to a full inline upload.
                final long uploadId = UploadIdHasher.contentHash(
                        payloads.size(), (int) frameDelayMs, 0, payloads);
                final OptionalLong acked = animationLoadAckService.ackedUploadId(serial, slot);
                if (acked.isPresent() && acked.getAsLong() == uploadId) {
                    animationLoadAckService.arm(serial, uploadId);
                    mqttClient.publish(serial + ANIM_PLAY_TOPIC, animPlayPayload(uploadId, slot), 1, false);
                    playbackStarted = animationLoadAckService.awaitLoaded(serial, uploadId, ANIM_PLAY_ACK_TIMEOUT);
                    mode = "hash-cached";
                }
                if (!playbackStarted) {
                    if (mode != null) {
                        log.warn("------> Play ack timeout for panel {} slot {}; re-uploading inline", serial, slot);
                    }
                    // Upload now; playback starts on the last frame.
                    final int codecBits = codecBitsFor(serial);
                    animationLoadAckService.arm(serial, uploadId);
                    boolean uploadCompleted = sendAnimationToPanel(frameDelayMs, payloads, uploadId, false, slot, codecBits);
                    playbackStarted = uploadCompleted
                            && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
                    mode = "inline upload";
                    if (!playbackStarted && codecBits == ANIM_CODEC_PAL_RLE) {
                        // Old/mixed-fleet firmware silently drops length-mismatched v2 ANIFs,
                        // so no ANIL ever comes: re-send the same content as codec-0 RAW under
                        // the same uploadId (the content hash is codec-blind) and keep the
                        // panel on RAW for the downgrade window.
                        log.warn("------> No anim/loaded ack for panel {} slot {} on a v2 upload; re-sending as RAW",
                                serial, slot);
                        animationLoadAckService.markDowngraded(serial);
                        animationLoadAckService.arm(serial, uploadId);
                        uploadCompleted = sendAnimationToPanel(frameDelayMs, payloads, uploadId, false, slot, ANIM_CODEC_RAW);
                        playbackStarted = uploadCompleted
                                && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
                        mode = "inline upload (raw fallback)";
                    }
                }
            }

            if (playbackStarted) {
                // Clear the retained base-topic image: while an animation is the current display
                // there is no static frame to retain, and a stale one (e.g. no_config_found.png
                // from before the panel was configured — animation-only rotations never publish
                // static frames, so it lingers forever) would be delivered to the panel on boot
                // and stop its auto-resumed slot animation. An empty retained publish clears it;
                // live panels ignore zero-length base-topic payloads.
                mqttClient.publish(serial, new byte[0], 1, true);
                log.info("------> Animation playing on panel {} after {} ms ({} frames, slot {}, {})",
                        serial, (System.nanoTime() - boundaryStartNanos) / 1_000_000, frames.size(), slot, mode);
            } else {
                log.warn("------> No anim/loaded ack from panel {} within timeout; counting slot from now", serial);
            }

            final BufferedImage firstFrame = frames.getFirst();
            final String imageFilename = "generated_images/" + serial + ".png";
            ImageIO.write(firstFrame, "PNG", new File(imageFilename));

            scaleImageAndPublish(firstFrame);
            final long deadlineNanos = System.nanoTime() + slotNanos;
            Thread.startVirtualThread(() ->
                    streamAnimationPreview(previewGeneration, frames, frameDelayMs, deadlineNanos));
        } catch (IOException | MqttException e) {
            throw new RuntimeException(e);
        }
    }

    /** Builds the batch stream, or null (logged) when the command rendering fails — caller falls back. */
    private CommandScreenService.BatchStream buildCommandBatches(
            final CommandScreenService<ScreenConfig> screenService, final ScreenConfig screenConfig) {
        try {
            return screenService.renderCommandBatches(screenConfig);
        } catch (final Exception e) {
            log.error("Command batch build failed for screen {} on panel {}; using the frame path",
                    screenConfig.screenType(), panelConfig.getPanelId(), e);
            return null;
        }
    }

    /**
     * {@code CommandScreenService#commandCapable} without the wildcard-capture headache:
     * false (never throwing) also for a service that is not a {@code CommandScreenService}
     * at all — the callers gate the command branch on this plus the flag.
     */
    @SuppressWarnings("unchecked")
    private static boolean isCommandCapable(final ScreenService<? extends ScreenConfig> screenService,
                                            final ScreenConfig screenConfig) {
        return screenService instanceof CommandScreenService<?>
                && screenConfig != null
                && ((CommandScreenService<ScreenConfig>) screenService).commandCapable(screenConfig);
    }

    /**
     * Builds the live-refresh stream (whose eager first render validates it), or null
     * when the screen offers no refresh or that first render fails — caller continues
     * with the page batches, then the frame path, exactly like a batch build failure.
     */
    private CommandScreenService.RefreshStream buildCommandRefresh(
            final CommandScreenService<ScreenConfig> screenService, final ScreenConfig screenConfig) {
        try {
            final CommandScreenService.RefreshStream refresh =
                    screenService.renderCommandRefresh(screenConfig);
            return refresh != null && refresh.refreshMs() > 0 ? refresh : null;
        } catch (final Exception e) {
            log.error("Command refresh build failed for screen {} on panel {}; using the batch path",
                    screenConfig.screenType(), panelConfig.getPanelId(), e);
            return null;
        }
    }

    /**
     * Live-refreshing command screens: like {@link #renderCommandScreen} (retained
     * base cleared, QoS-0 fire-and-forget batch, slot counted from the send, SSE
     * preview from the {@link AcmdMirror}), but two virtual threads run the slot:
     * {@link #refreshCommandBatches} re-renders and republishes on the refresh grid,
     * and {@link #streamRefreshingCommandPreview} samples whatever mirror is current
     * on the slot-anchored timeline (each refresh interval is a whole parametric
     * loop by contract, so the sweep phase stays continuous across swaps).
     */
    private void renderRefreshingCommandScreen(final CommandScreenService.RefreshStream refresh,
                                               final ScreenConfig screenConfig, final int previewGeneration) {
        final String serial = px75Panel.getSerial();
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();
        try {
            mqttClient.publish(serial, new byte[0], 1, true);
            final byte[] firstBatch = refresh.firstBatch();
            final long sendNanos = System.nanoTime();
            publishCommandBatch(previewGeneration, firstBatch);
            log.info("------> Command batch ({} bytes, refreshing every {} ms) sent to panel {} for screen {}",
                    firstBatch.length, refresh.refreshMs(), serial, screenConfig.screenType());

            final AtomicReference<AcmdMirror> mirrorRef = new AtomicReference<>(AcmdMirror.parse(firstBatch));

            final BufferedImage baseImage = AcmdMirror.toBufferedImage(mirrorRef.get().frameAt(0));
            final String imageFilename = "generated_images/" + serial + ".png";
            ImageIO.write(baseImage, "PNG", new File(imageFilename));
            scaleImageAndPublish(baseImage);
            final long deadlineNanos = sendNanos + slotNanos;
            Thread.startVirtualThread(() ->
                    streamRefreshingCommandPreview(previewGeneration, mirrorRef, sendNanos, deadlineNanos));
            Thread.startVirtualThread(() ->
                    refreshCommandBatches(previewGeneration, refresh, mirrorRef, sendNanos, deadlineNanos));
        } catch (IOException | MqttException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Refresh publisher: renders each next batch during the interval before its grid
     * point (pipelined, so publishes land exactly one refresh apart regardless of a
     * slow fetch) and republishes at every {@code refreshMs} boundary of the slot.
     * A slow or failed render skips grid points instead of publishing late — any
     * whole multiple of the refresh interval still ends the previous parametric
     * exactly where the new one re-arms (seamless by the {@code RefreshStream}
     * contract), while a late publish would visibly snap the sweep back. Stops like
     * the other slot threads: superseded by a newer render, slot end, interruption.
     */
    private void refreshCommandBatches(final int generation, final CommandScreenService.RefreshStream refresh,
                                       final AtomicReference<AcmdMirror> mirrorRef,
                                       final long sendNanos, final long deadlineNanos) {
        final long refreshNanos = refresh.refreshMs() * 1_000_000L;
        long nextPublishNanos = sendNanos + refreshNanos;
        while (running.get()
                && !Thread.currentThread().isInterrupted()
                && generation == currentPreviewGeneration()
                && System.nanoTime() < deadlineNanos) {
            byte[] nextBatch = null;
            try {
                nextBatch = refresh.nextBatches().get();
            } catch (final Exception e) {
                log.error("Command refresh render failed for panel {}; keeping the last batch on display",
                        panelConfig.getPanelId(), e);
            }
            sleepUntil(nextPublishNanos);
            // Advance the grid past everything the render consumed: a publish that
            // cannot land on time is dropped to the next grid point, never late.
            do {
                nextPublishNanos += refreshNanos;
            } while (nextPublishNanos <= System.nanoTime());

            if (!running.get() || Thread.currentThread().isInterrupted()
                    || generation != currentPreviewGeneration()
                    || System.nanoTime() >= deadlineNanos) {
                return;
            }
            if (nextBatch == null) {
                continue; // render failed this interval; the panel keeps the previous batch
            }
            try {
                final AcmdMirror mirror = AcmdMirror.parse(nextBatch);
                publishCommandBatch(generation, nextBatch);
                mirrorRef.set(mirror);
                log.debug("------> Command refresh ({} bytes) sent to panel {}", nextBatch.length,
                        px75Panel.getSerial());
            } catch (final MqttException e) {
                log.error("Failed to publish command refresh for panel {}", panelConfig.getPanelId(), e);
                return;
            }
        }
    }

    /**
     * Refreshing variant of {@link #streamCommandPreview}: samples the latest mirror
     * (swapped at each refresh publish) but keeps the slot-anchored timeline — each
     * refresh interval is a whole parametric loop by contract, so the sweep phase is
     * continuous across swaps and the elapsed time never resets.
     */
    private void streamRefreshingCommandPreview(final int generation, final AtomicReference<AcmdMirror> mirrorRef,
                                                final long sendNanos, final long deadlineNanos) {
        while (running.get()
                && generation == currentPreviewGeneration()
                && System.nanoTime() < deadlineNanos) {
            try {
                final long elapsedMs = Math.max(0, (System.nanoTime() - sendNanos) / 1_000_000);
                scaleImageAndPublish(AcmdMirror.toBufferedImage(mirrorRef.get().frameAt(elapsedMs)));
            } catch (IOException e) {
                log.error("Failed to broadcast command preview frame for panel {}", panelConfig.getPanelId(), e);
                return;
            }
            try {
                Thread.sleep(COMMAND_PREVIEW_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Sleeps until {@code targetNanos} (one coarse ms-granularity sleep; loop conditions re-checked after). */
    private static void sleepUntil(final long targetNanos) {
        final long millis = (targetNanos - System.nanoTime()) / 1_000_000;
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * ACMD v1 command path (plan §6): publishes the first fully framed batch to
     * {@code <serial>/cmd} — QoS 0, not retained, no ack (fire-and-forget) — and counts
     * the slot from the send time. Multi-page screens ({@link CommandScreenService.BatchStream}
     * with a dwell) cycle their remaining batches at each page boundary via
     * {@link #cycleCommandPages}. Command screens publish no retained base image; any
     * retained frame left by an earlier static screen is cleared with an empty retained
     * publish (live panels ignore zero-length base payloads; a panel booting mid-slot
     * must not be served a stale screen). The SSE preview is driven from the
     * {@link AcmdMirror} on the slot's virtual timeline — exactly what an ACMD panel
     * renders, so preview parity comes for free.
     */
    private void renderCommandScreen(final CommandScreenService.BatchStream stream,
                                     final ScreenConfig screenConfig, final int previewGeneration) {
        final String serial = px75Panel.getSerial();
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();
        try {
            mqttClient.publish(serial, new byte[0], 1, true);
            final byte[] firstBatch = stream.batches().getFirst();
            final long sendNanos = System.nanoTime();
            publishCommandBatch(previewGeneration, firstBatch);
            log.info("------> Command batch ({} bytes, {} pages) sent to panel {} for screen {}",
                    firstBatch.length, stream.batches().size(), serial, screenConfig.screenType());

            final List<AcmdMirror> mirrors = new java.util.ArrayList<>(stream.batches().size());
            for (final byte[] pageBatch : stream.batches()) {
                mirrors.add(AcmdMirror.parse(pageBatch));
            }

            final BufferedImage baseImage = AcmdMirror.toBufferedImage(mirrors.getFirst().frameAt(0));
            final String imageFilename = "generated_images/" + serial + ".png";
            ImageIO.write(baseImage, "PNG", new File(imageFilename));
            scaleImageAndPublish(baseImage);
            final long deadlineNanos = sendNanos + slotNanos;
            if (stream.batches().size() == 1 || stream.pageDwellMs() <= 0) {
                final AcmdMirror mirror = mirrors.getFirst();
                Thread.startVirtualThread(() ->
                        streamCommandPreview(previewGeneration, mirror, sendNanos, deadlineNanos));
            } else {
                Thread.startVirtualThread(() ->
                        cycleCommandPages(previewGeneration, stream, mirrors, sendNanos, deadlineNanos));
            }
        } catch (IOException | MqttException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Publishes one command batch to {@code <serial>/cmd} while holding the serial's
     * preview-generation monitor, re-checking the generation inside it: a newer render
     * (rotation boundary, config save) bumps the generation before its own batch
     * publish, so a stale page-flip thread can never land an old page after that — at
     * worst its page lands just before it and the wire order keeps the new batch final.
     */
    private void publishCommandBatch(final int generation, final byte[] batch) throws MqttException {
        final AtomicInteger generationCounter = previewGenerations
                .computeIfAbsent(px75Panel.getSerial(), k -> new AtomicInteger());
        synchronized (generationCounter) {
            if (generation != generationCounter.get()) {
                return; // superseded while waiting for the monitor
            }
            mqttClient.publish(px75Panel.getSerial() + CMD_TOPIC, batch, 0, false);
        }
    }

    /**
     * Page-cycling command screens: flips {@code <serial>/cmd} to the next batch at
     * each {@code pageDwellMs} boundary (each page's parametrics restart at its flip,
     * exactly what the panel does on a fresh batch) and drives the SSE preview from
     * that page's mirror on the same virtual timeline. Mirrors the frame path's
     * page-stream semantics: one pass through the pages, the last page holds until the
     * slot ends. Stops like {@link #streamCommandPreview}: superseded by a newer
     * render, slot end, or interruption.
     */
    private void cycleCommandPages(final int generation, final CommandScreenService.BatchStream stream,
                                   final List<AcmdMirror> mirrors, final long sendNanos,
                                   final long deadlineNanos) {
        final List<byte[]> batches = stream.batches();
        final long pageDwellMs = stream.pageDwellMs();
        int currentPage = 0;
        while (running.get()
                && generation == currentPreviewGeneration()
                && System.nanoTime() < deadlineNanos) {
            final long elapsedMs = Math.max(0, (System.nanoTime() - sendNanos) / 1_000_000);
            final int pageIndex = (int) Math.min(elapsedMs / pageDwellMs, (long) (batches.size() - 1));
            if (pageIndex != currentPage) {
                currentPage = pageIndex;
                final byte[] pageBatch = batches.get(currentPage);
                try {
                    publishCommandBatch(generation, pageBatch);
                    log.debug("------> Command page {} ({} bytes) sent to panel {}",
                            currentPage, pageBatch.length, px75Panel.getSerial());
                } catch (MqttException e) {
                    log.error("Failed to publish command page for panel {}", panelConfig.getPanelId(), e);
                }
            }
            try {
                scaleImageAndPublish(AcmdMirror.toBufferedImage(
                        mirrors.get(currentPage).frameAt(elapsedMs - currentPage * pageDwellMs)));
            } catch (IOException e) {
                log.error("Failed to broadcast command preview frame for panel {}", panelConfig.getPanelId(), e);
                return;
            }
            try {
                Thread.sleep(COMMAND_PREVIEW_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Streams the SSE preview of a command screen from the {@link AcmdMirror} at a fixed
     * tick on the slot's virtual timeline (elapsed since the batch send), mirroring
     * {@link #streamAnimationPreview}: stops early once superseded by a newer render
     * (generation bump), when the slot ends, or when interrupted.
     */
    private void streamCommandPreview(final int generation, final AcmdMirror mirror,
                                      final long sendNanos, final long deadlineNanos) {
        while (running.get()
                && generation == currentPreviewGeneration()
                && System.nanoTime() < deadlineNanos) {
            try {
                final long elapsedMs = Math.max(0, (System.nanoTime() - sendNanos) / 1_000_000);
                scaleImageAndPublish(AcmdMirror.toBufferedImage(mirror.frameAt(elapsedMs)));
            } catch (IOException e) {
                log.error("Failed to broadcast command preview frame for panel {}", panelConfig.getPanelId(), e);
                return;
            }
            try {
                Thread.sleep(COMMAND_PREVIEW_TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Uploads the next rotation screen's frame stream to the panel right away, while the current
     * screen is displaying (stage-only: the panel stores it in its slot file and waits for
     * {@code /anim/play} at the boundary). Skipped for screens that opt out of staging via
     * {@link FrameScreenConfig#stageAhead()} — those render fresh at the boundary instead, so
     * their frames reflect the latest data at display time. Runs at full speed — the panel
     * applies its own flow control (MQTT backpressure) while it drains frames to flash without
     * disturbing what's on display. Skipped when the target slot is the one currently playing (a
     * single-animation rotation, or slot wraparound past 32 animations): replacing it would
     * freeze the playing animation, so that boundary uploads inline instead. Returns the
     * wall-clock millis spent; the caller subtracts them from the current slot.
     */
    private long stageNextAnimation(final int currentIdx, final List<? extends ScreenConfig> configs) {
        stagedNextIdx = -1;

        final int n = configs.size();
        final int nextIdx = (currentIdx + 1) % n;
        if (!(configs.get(nextIdx) instanceof FrameScreenConfig animConfig)
                || !animConfig.producesFrames()
                || !animConfig.stageAhead()) {
            return 0;
        }

        // That boundary will render via ACMD commands instead (only command-capable configs —
        // CUSTOM frame designs keep ANIM staging): uploading the frame stream now would be
        // dead weight (flash writes for a batch the panel never plays).
        if (commandEncodingEnabled
                && screenServices.get(animConfig.screenType()) instanceof CommandScreenService<?>
                && isCommandCapable(screenServices.get(animConfig.screenType()), animConfig)) {
            return 0;
        }

        final int nextSlot = animationSlotFor(nextIdx, configs);
        if (producesFrames(configs.get(currentIdx)) && animationSlotFor(currentIdx, configs) == nextSlot) {
            return 0; // staging would clobber the slot the current animation is playing from
        }

        final long stageStartNanos = System.nanoTime();
        final String serial = px75Panel.getSerial();

        try {
            final FrameScreenService frameScreenService =
                    (FrameScreenService) screenServices.get(animConfig.screenType());
            final FrameScreenService.FrameStream stagedStream = frameScreenService.renderFrameStream(animConfig);
            final List<BufferedImage> frames = stagedStream.frames();
            final long frameDelayMs = stagedStream.frameDelayMs();
            final List<byte[]> payloads = framePayloads(frames);

            final long uploadId = UploadIdHasher.contentHash(
                    payloads.size(), (int) frameDelayMs, ANIM_FLAG_STAGE_ONLY, payloads);
            final OptionalLong acked = animationLoadAckService.ackedUploadId(serial, nextSlot);
            if (acked.isPresent() && acked.getAsLong() == uploadId) {
                // The panel's slot still holds this exact content (it acked this uploadId
                // before): skip the upload entirely — the boundary commits it with a 9-byte
                // /anim/play like any staged animation, and re-uploads inline if the panel
                // has since lost the slot.
                stagedNextIdx = nextIdx;
                stagedNextUploadId = uploadId;
                log.debug("------> Animation slot {} (screen {}) on panel {} unchanged (uploadId {}); skipping upload",
                        nextSlot, nextIdx, serial, uploadId);
                return (System.nanoTime() - stageStartNanos) / 1_000_000;
            }

            final int codecBits = codecBitsFor(serial);
            animationLoadAckService.arm(serial, uploadId);
            boolean uploadCompleted = sendAnimationToPanel(frameDelayMs, payloads, uploadId, true, nextSlot, codecBits);
            boolean staged = uploadCompleted
                    && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
            if (!staged && codecBits == ANIM_CODEC_PAL_RLE) {
                // Same old-firmware signal as the inline path: the v2 frames were silently
                // dropped, so stage the same content as codec-0 RAW under the same uploadId
                // and keep the panel on RAW for the downgrade window.
                log.warn("------> No anim/loaded ack for panel {} slot {} on a v2 staged upload; re-sending as RAW",
                        serial, nextSlot);
                animationLoadAckService.markDowngraded(serial);
                animationLoadAckService.arm(serial, uploadId);
                uploadCompleted = sendAnimationToPanel(frameDelayMs, payloads, uploadId, true, nextSlot, ANIM_CODEC_RAW);
                staged = uploadCompleted
                        && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
            }
            if (staged) {
                stagedNextIdx = nextIdx;
                stagedNextUploadId = uploadId;
                log.debug("------> Staged animation slot {} (screen {}) on panel {} ({} frames)",
                        nextSlot, nextIdx, serial, frames.size());
            }
        } catch (Exception e) {
            log.error("Failed to stage animation for screen {} on panel {}; falling back to inline upload",
                    nextIdx, panelConfig.getPanelId(), e);
        }

        return (System.nanoTime() - stageStartNanos) / 1_000_000;
    }

    /** The upload staged for screen {@code idx}, or null if there is none (and drop it either way). */
    private Long consumeStaged(final int idx) {
        if (stagedNextIdx != idx) {
            stagedNextIdx = -1;
            return null;
        }
        stagedNextIdx = -1;
        return stagedNextUploadId;
    }

    /** Slot index for a frame-streaming screen: its ordinal among the rotation's frame screens, capped. */
    private static int animationSlotFor(final int screenIdx, final List<? extends ScreenConfig> configs) {
        int ordinal = 0;
        for (int i = 0; i <= screenIdx; i++) {
            if (producesFrames(configs.get(i))) {
                ordinal++;
            }
        }
        return (ordinal - 1) % MAX_ANIM_SLOTS;
    }

    private static boolean producesFrames(final ScreenConfig config) {
        return config instanceof FrameScreenConfig frameConfig && frameConfig.producesFrames();
    }

    /** Raw RGB565 payload bytes per frame — the content hashed by {@link UploadIdHasher}. */
    private List<byte[]> framePayloads(final List<BufferedImage> frames) {
        final List<byte[]> payloads = new java.util.ArrayList<>(frames.size());
        for (final BufferedImage frame : frames) {
            payloads.add(imageService.bufferedImageToBytes(frame, 0, 0));
        }
        return payloads;
    }

    private static Duration uploadAckTimeout(final int frameCount) {
        return Duration.ofMillis(Math.min(60_000, 5_000 + frameCount * 250L));
    }

    /**
     * Streams the animation frames to the SSE live preview at the configured frame delay for the
     * screen's time slot. Runs on its own virtual thread and stops early once superseded by a
     * newer render (generation bump), when the slot ends, or when interrupted.
     */
    private void streamAnimationPreview(final int generation, final List<BufferedImage> frames,
                                        final long frameDelayMs, final long deadlineNanos) {
        int i = 1;
        while (running.get()
                && generation == currentPreviewGeneration()
                && System.nanoTime() < deadlineNanos) {
            try {
                scaleImageAndPublish(frames.get(i % frames.size()));
            } catch (IOException e) {
                log.error("Failed to broadcast animation preview frame for panel {}", panelConfig.getPanelId(), e);
                return;
            }
            i++;
            try {
                Thread.sleep(frameDelayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private int bumpPreviewGeneration() {
        return previewGenerations
                .computeIfAbsent(px75Panel.getSerial(), k -> new AtomicInteger())
                .incrementAndGet();
    }

    private int currentPreviewGeneration() {
        final AtomicInteger generation = previewGenerations.get(px75Panel.getSerial());
        return generation == null ? 0 : generation.get();
    }

    private boolean sendAnimationToPanel(final long frameDelayMs,
                                         final List<byte[]> payloads,
                                         final long uploadId,
                                         final boolean stageOnly,
                                         final int slot,
                                         final int codecBits) throws MqttException {
        final String serial = px75Panel.getSerial();

        log.debug("------> Uploading animation ({} frames, {} ms delay, slot {}, stageOnly={}, codec={}) to panel {}",
                payloads.size(), frameDelayMs, slot, stageOnly,
                codecBits == ANIM_CODEC_PAL_RLE ? "PAL_RLE" : "RAW", serial);

        final int flags = (stageOnly ? ANIM_FLAG_STAGE_ONLY : 0) | codecBits;
        mqttClient.publish(serial + ANIM_START_TOPIC,
                animStartPayload(payloads.size(), (int) frameDelayMs, uploadId, flags, slot), 1, false);

        // Full speed: the panel buffers frames in RAM and drains them to flash at its own
        // pace (MQTT flow control throttles this loop automatically), so no pacing needed.
        for (int i = 0; i < payloads.size(); i++) {
            mqttClient.publish(serial + ANIM_FRAME_TOPIC, animFramePayload(i, payloads.get(i), codecBits), 1, false);
        }

        return true;
    }

    /** Codec for this panel's next upload: RAW inside the v2 downgrade window, else PAL_RLE. */
    private int codecBitsFor(final String serial) {
        return animationLoadAckService.prefersRaw(serial) ? ANIM_CODEC_RAW : ANIM_CODEC_PAL_RLE;
    }

    private static byte[] animStartPayload(final int frameCount, final int frameDelayMs,
                                           final long uploadId, final int flags, final int slot) {
        return new byte[]{
                'A', 'N', 'I', 'M',
                (byte) frameCount, (byte) (frameCount >> 8),
                (byte) frameDelayMs, (byte) (frameDelayMs >> 8),
                (byte) uploadId, (byte) (uploadId >> 8),
                (byte) (uploadId >> 16), (byte) (uploadId >> 24),
                (byte) flags,
                (byte) slot
        };
    }

    private static byte[] animPlayPayload(final long uploadId, final int slot) {
        return new byte[]{
                'A', 'N', 'I', 'P',
                (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8),
                (byte) (uploadId >> 16), (byte) (uploadId >> 24)
        };
    }

    private static byte[] animFramePayload(final int frameIdx, final byte[] rgb565, final int codecBits) {
        if (codecBits == ANIM_CODEC_RAW) {
            final byte[] payload = new byte[6 + rgb565.length];
            payload[0] = 'A';
            payload[1] = 'N';
            payload[2] = 'I';
            payload[3] = 'F';
            payload[4] = (byte) frameIdx;
            payload[5] = (byte) (frameIdx >> 8);
            System.arraycopy(rgb565, 0, payload, 6, rgb565.length);
            return payload;
        }
        final AnimationFrameCodec.EncodedFrame encoded = AnimationFrameCodec.encode(rgb565);
        final byte[] payload = new byte[7 + encoded.body().length];
        payload[0] = 'A';
        payload[1] = 'N';
        payload[2] = 'I';
        payload[3] = 'F';
        payload[4] = (byte) frameIdx;
        payload[5] = (byte) (frameIdx >> 8);
        payload[6] = (byte) encoded.frameFlags();
        System.arraycopy(encoded.body(), 0, payload, 7, encoded.body().length);
        return payload;
    }

    private void sendImageToPanel(final BufferedImage screenImage) {
        try {
            final MqttMessage mqttMessage = new MqttMessage(imageService.bufferedImageToBytes(screenImage, 0, 0));
            mqttMessage.setRetained(true);
            mqttClient.publish(px75Panel.getSerial(), mqttMessage);

            final String imageFilename = "generated_images/" + px75Panel.getSerial() + ".png";
            ImageIO.write(screenImage, "PNG", new File(imageFilename));

            scaleImageAndPublish(screenImage);
        } catch (IOException | MqttException e) {
            throw new RuntimeException(e);
        }
    }

    private void scaleImageAndPublish(final BufferedImage image) throws IOException {
        final BufferedImage scaledImage = imageService.scale(image, 640, 320);
        imageBroadcasterService.updateLatest(px75Panel.getSerial(), scaledImage);
    }

    int getScreenIndex(final List<? extends ScreenConfig> screens) {
        if (screenIndex.incrementAndGet() >= screens.size()) {
            screenIndex.set(0);
        }

        return screenIndex.get();
    }

    /**
     * Working copy of the rotation for this cycle: CUSTOM library references hydrated to
     * their current design (so library edits apply without a panel re-save), dangling
     * references dropped with a warning. The stored entity keeps its references — this
     * list is never persisted.
     */
    private List<? extends ScreenConfig> hydratedScreens(final List<? extends ScreenConfig> rawConfig) {
        boolean anyReference = false;
        for (final ScreenConfig config : rawConfig) {
            if (config instanceof CustomScreenConfig custom && custom.customScreenId() != null) {
                anyReference = true;
                break;
            }
        }
        if (!anyReference) {
            return rawConfig;
        }
        final List<ScreenConfig> hydrated = new java.util.ArrayList<>(rawConfig.size());
        for (final ScreenConfig config : rawConfig) {
            if (config instanceof CustomScreenConfig custom && custom.customScreenId() != null) {
                final CustomScreenConfig resolved = customScreenResolver.hydrate(custom);
                if (resolved == null) {
                    log.warn("------> Custom screen {} referenced by panel {} no longer exists; skipping it",
                            custom.customScreenId(), panelConfig.getPanelId());
                    continue;
                }
                hydrated.add(resolved);
                continue;
            }
            hydrated.add(config);
        }
        return List.copyOf(hydrated);
    }
}
