package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.ScreenServiceNotFoundException;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.*;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.AircraftScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.animation.AnimationScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.clock.ClockScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.CryptoScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.date.DateScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.formula1.Formula1ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.image.ImageScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatchService;
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
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class PanelScreenJob implements ReschedulableJob {
    static final String ANIM_START_TOPIC = "/anim/start";
    static final String ANIM_FRAME_TOPIC = "/anim/frame";
    static final String ANIM_PLAY_TOPIC = "/anim/play";
    static final byte ANIM_FLAG_STAGE_ONLY = 0x01;

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
                          final ConcurrentMap<String, AtomicInteger> previewGenerations) {
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
        this.id = px75Panel.getSerial();
    }

    @EventListener
    public void onContextClosed(ContextClosedEvent e) {
        running.set(false);
    }

    @Override
    public Optional<Duration> run() {

        final List<? extends ScreenConfig> screensConfig = panelConfig.getScreensConfig();

        if (screensConfig == null || screensConfig.isEmpty()) {
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

        if (!running.get()) {
            log.warn("Not re-scheduling screen jobs for panel, because it's ending: {}", panelConfig.getPanelId());
        }

        if (Thread.currentThread().isInterrupted()) {
            // Vervangen/gestopt door een config-save of paneel-delete: niet meer publiceren.
            log.debug("----> Job for panel {} interrupted before render, skipping", panelConfig.getPanelId());
            return Optional.empty();
        }

        final int previewGeneration = bumpPreviewGeneration();
        final int screenIdx = getScreenIndex();
        final ScreenConfig screenConfig = screensConfig.get(screenIdx);

        long durationMillis;
        try {
            log.debug("----> Next screen job for panel: {}", panelConfig.getPanelId());
            renderScreen(screenConfig, previewGeneration, screenIdx);
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
        final long stagedMillis = stageNextAnimation(screenIdx, screensConfig);
        durationMillis = Math.max(MIN_ANIMATION_SLOT_MILLIS,
                Duration.ofSeconds(screenConfig.durationSeconds()).toMillis() - stagedMillis);

        return Optional.of(Duration.ofMillis(durationMillis));

    }

    void renderScreen(final ScreenConfig screenConfig, final int previewGeneration, final int screenIdx) {
        final ScreenService<? extends ScreenConfig> screenService = screenServices.get(screenConfig.screenType());
        if (screenService == null) {
            throw new ScreenServiceNotFoundException(screenConfig.screenType());
        }

        log.debug("------> Rendering screen {} for panel {}", screenConfig.screenType(), panelConfig.getPanelId());

        if (screenConfig instanceof FrameScreenConfig frameScreenConfig && frameScreenConfig.producesFrames()) {
            renderFrameScreen((FrameScreenService) screenService, frameScreenConfig, previewGeneration, screenIdx);
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
            case AnimationScreenConfig a -> ((AnimationScreenService) screenService).renderScreen(a);
        };

        screenImage.ifPresent(this::sendImageToPanel);
    }

    private <T extends FrameScreenConfig> void renderFrameScreen(final FrameScreenService<T> screenService,
                                                                 final T screenConfig,
                                                                 final int previewGeneration,
                                                                 final int screenIdx) {
        final FrameScreenService.FrameStream stream = screenService.renderFrameStream(screenConfig);
        final List<BufferedImage> frames = stream.frames();
        final long frameDelayMs = stream.frameDelayMs();
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();
        final String serial = px75Panel.getSerial();
        final int slot = animationSlotFor(screenIdx, panelConfig.getScreensConfig());
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
                    animationLoadAckService.arm(serial, uploadId);
                    final boolean uploadCompleted = sendAnimationToPanel(frameDelayMs, payloads, uploadId, false, slot);
                    playbackStarted = uploadCompleted
                            && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
                    mode = "inline upload";
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

            animationLoadAckService.arm(serial, uploadId);
            final boolean uploadCompleted = sendAnimationToPanel(frameDelayMs, payloads, uploadId, true, nextSlot);
            final boolean staged = uploadCompleted
                    && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
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
                                         final int slot) throws MqttException {
        final String serial = px75Panel.getSerial();

        log.debug("------> Uploading animation ({} frames, {} ms delay, slot {}, stageOnly={}) to panel {}",
                payloads.size(), frameDelayMs, slot, stageOnly, serial);

        mqttClient.publish(serial + ANIM_START_TOPIC,
                animStartPayload(payloads.size(), (int) frameDelayMs, uploadId, stageOnly, slot), 1, false);

        // Full speed: the panel buffers frames in RAM and drains them to flash at its own
        // pace (MQTT flow control throttles this loop automatically), so no pacing needed.
        for (int i = 0; i < payloads.size(); i++) {
            mqttClient.publish(serial + ANIM_FRAME_TOPIC, animFramePayload(i, payloads.get(i)), 1, false);
        }

        return true;
    }

    private static byte[] animStartPayload(final int frameCount, final int frameDelayMs,
                                           final long uploadId, final boolean stageOnly, final int slot) {
        return new byte[]{
                'A', 'N', 'I', 'M',
                (byte) frameCount, (byte) (frameCount >> 8),
                (byte) frameDelayMs, (byte) (frameDelayMs >> 8),
                (byte) uploadId, (byte) (uploadId >> 8),
                (byte) (uploadId >> 16), (byte) (uploadId >> 24),
                (byte) (stageOnly ? ANIM_FLAG_STAGE_ONLY : 0),
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

    private static byte[] animFramePayload(final int frameIdx, final byte[] rgb565) {
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

    int getScreenIndex() {
        if (screenIndex.incrementAndGet() >= panelConfig.getScreensConfig().size()) {
            screenIndex.set(0);
        }

        return screenIndex.get();
    }
}
