package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

/**
 * The ANIM transport extracted from the old {@code PanelScreenJob}: payload builders, the
 * one-ahead staging state machine, and the slot-boundary commit (staged → ANIP, hash-cached
 * → ANIP, inline upload with the v2→RAW downgrade fallback). All MQTT traffic for the
 * {@code /anim/*} topics lives here; the state it mutates between cycles (the staged-next
 * marker) is the per-serial state in {@link RotationStateService}.
 */
@Slf4j
@Service
public class AnimationTransport {

    static final String ANIM_START_TOPIC = "/anim/start";
    static final String ANIM_FRAME_TOPIC = "/anim/frame";
    static final String ANIM_PLAY_TOPIC = "/anim/play";
    static final byte ANIM_FLAG_STAGE_ONLY = 0x01;
    static final int ANIM_CODEC_MASK = 0x06;
    static final int ANIM_CODEC_RAW = 0x00;
    static final int ANIM_CODEC_PAL_RLE = 0x02;

    /** Play commit on the panel is a rename + first-frame draw (ms); cap before inline fallback. */
    static final Duration ANIM_PLAY_ACK_TIMEOUT = Duration.ofSeconds(2);

    /**
     * Floor for a slot shortened because the next animation was staged during it. Normally the
     * staging fits inside the slot and the boundary stays on schedule; if it overruns, the
     * previous screen simply holds until staging completes (the slot never renders half done).
     */
    static final long MIN_ANIMATION_SLOT_MILLIS = 1000;

    /**
     * Bounded in-flight window for the {@code /anim/*} publishes (plan §5): Paho's sync
     * client blocked per frame until the broker acked, which accidentally paced the upload
     * to MQTT flow control — exactly what the firmware's 8×4 KB RAM ring expects while it
     * drains frames to flash. Unbounded async publishing would overflow the panel's RAM,
     * so the async transport is gated by this semaphore; the permit returns when the
     * broker acknowledges the message (QoS 1 PUBACK).
     */
    static final int MAX_ANIM_MESSAGES_IN_FLIGHT = 8;

    private final Semaphore animMessagesInFlight = new Semaphore(MAX_ANIM_MESSAGES_IN_FLIGHT);

    private final ImageService imageService;
    private final AnimationLoadAckService animationLoadAckService;
    private final ScreenServices screenServices;
    private final RotationStateService rotationStateService;
    private final MqttTransport mqttTransport;

    /**
     * {@code pixelcore75.command-encoding.enabled} (default false, mixed-fleet safety):
     * a next screen that will render via ACMD commands must not get its frame stream staged.
     */
    private final boolean commandEncodingEnabled;

    public AnimationTransport(final ImageService imageService,
                              final AnimationLoadAckService animationLoadAckService,
                              final ScreenServices screenServices,
                              final RotationStateService rotationStateService,
                              final MqttTransport mqttTransport,
                              @Value("${pixelcore75.command-encoding.enabled:false}") final boolean commandEncodingEnabled) {
        this.imageService = imageService;
        this.animationLoadAckService = animationLoadAckService;
        this.screenServices = screenServices;
        this.rotationStateService = rotationStateService;
        this.mqttTransport = mqttTransport;
        this.commandEncodingEnabled = commandEncodingEnabled;
    }

    /** Raw RGB565 payload bytes per frame — the content hashed by {@link UploadIdHasher}. */
    public List<byte[]> framePayloads(final List<BufferedImage> frames) {
        final List<byte[]> payloads = new ArrayList<>(frames.size());
        for (final BufferedImage frame : frames) {
            payloads.add(imageService.bufferedImageToBytes(frame, 0, 0));
        }
        return payloads;
    }

    /**
     * Uploads the next rotation screen's frame stream to the panel right away, while the current
     * screen is displaying (stage-only: the panel stores it in its slot file and waits for
     * {@code /anim/play} at the boundary). Skipped for screens that opt out of staging via
     * {@link FrameScreenConfig#stageAhead()} — those render fresh at the boundary instead, so
     * their frames reflect the latest data at display time. Skipped when the target slot is the
     * one currently playing (a single-animation rotation, or slot wraparound past 32 animations).
     * Runs at full speed — the panel applies its own flow control. Skipped when the next screen
     * will render via ACMD commands (dead weight). Returns the wall-clock millis spent; the
     * caller subtracts them from the current slot.
     */
    public long stageNext(final String serial, final String jobId, final int currentIdx,
                          final List<? extends ScreenConfig> configs) {
        final RotationStateService.RotationState state = rotationStateService.stateFor(serial);
        rotationStateService.clearStaged(state);

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
                && commandCapable(animConfig.screenType(), animConfig)) {
            return 0;
        }

        final int nextSlot = RotationPlanner.animationSlotFor(nextIdx, configs);
        if (RotationPlanner.producesFrames(configs.get(currentIdx))
                && RotationPlanner.animationSlotFor(currentIdx, configs) == nextSlot) {
            return 0; // staging would clobber the slot the current animation is playing from
        }

        final long stageStartNanos = System.nanoTime();

        try {
            final FrameScreenService<FrameScreenConfig> frameScreenService = frameService(animConfig.screenType());
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
                rotationStateService.setStaged(state, nextIdx, uploadId);
                log.debug("------> Animation slot {} (screen {}) on panel {} unchanged (uploadId {}); skipping upload",
                        nextSlot, nextIdx, serial, uploadId);
                return elapsedMillis(stageStartNanos);
            }

            final int codecBits = codecBitsFor(serial);
            animationLoadAckService.arm(serial, uploadId);
            boolean uploadCompleted = upload(serial, frameDelayMs, payloads, uploadId, true, nextSlot, codecBits, jobId);
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
                uploadCompleted = upload(serial, frameDelayMs, payloads, uploadId, true, nextSlot, ANIM_CODEC_RAW, jobId);
                staged = uploadCompleted
                        && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
            }
            if (staged) {
                rotationStateService.setStaged(state, nextIdx, uploadId);
                log.debug("------> Staged animation slot {} (screen {}) on panel {} ({} frames)",
                        nextSlot, nextIdx, serial, frames.size());
            }
        } catch (Exception e) {
            log.error("Failed to stage animation for screen {} on panel {}; falling back to inline upload",
                    nextIdx, serial, e);
        }

        return elapsedMillis(stageStartNanos);
    }

    /**
     * The slot-boundary commit for a frame screen: consume any staged upload (or recognize
     * the panel slot still holding the exact content) as a 9-byte {@code /anim/play}, fall
     * back to a full inline upload (with the v2→RAW downgrade) when the panel stays silent.
     * Returns true once playback started, so the caller counts the slot from playback start
     * and clears the retained base-topic image.
     */
    public boolean commitAtBoundary(final String serial, final String jobId, final int screenIdx,
                                    final List<? extends ScreenConfig> configs,
                                    final List<BufferedImage> frames, final long frameDelayMs) {
        final RotationStateService.RotationState state = rotationStateService.stateFor(serial);
        final int slot = RotationPlanner.animationSlotFor(screenIdx, configs);
        boolean playbackStarted = false;
        String mode = null;

        final List<byte[]> payloads = framePayloads(frames);
        if (rotationStateService.isStagedFor(state, screenIdx)) {
            final long staged = rotationStateService.stagedUploadId(state);
            rotationStateService.consumeStaged(state);
            // Uploaded to the panel during the previous screen (or still sitting in its
            // slot from an earlier cycle — hash-cached): committing is just a play (a
            // rename when freshly uploaded), no flash writes while anything displays.
            animationLoadAckService.arm(serial, staged);
            publishAnim(serial + ANIM_PLAY_TOPIC, animPlayPayload(staged, slot));
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
                publishAnim(serial + ANIM_PLAY_TOPIC, animPlayPayload(uploadId, slot));
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
                boolean uploadCompleted = upload(serial, frameDelayMs, payloads, uploadId, false, slot, codecBits, jobId);
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
                    uploadCompleted = upload(serial, frameDelayMs, payloads, uploadId, false, slot, ANIM_CODEC_RAW, jobId);
                    playbackStarted = uploadCompleted
                            && animationLoadAckService.awaitLoaded(serial, uploadId, uploadAckTimeout(frames.size()));
                    mode = "inline upload (raw fallback)";
                }
            }
        }

        if (playbackStarted) {
            log.info("------> Animation playing on panel {} ({} frames, slot {}, {})",
                    serial, frames.size(), slot, mode);
        } else {
            log.warn("------> No anim/loaded ack from panel {} within timeout; counting slot from now", serial);
        }
        return playbackStarted;
    }

    /**
     * Whether the screen at {@code screenType} with this config renders via ACMD commands
     * (flag is checked by the caller; this is the per-config {@code commandCapable} gate
     * without the wildcard-capture headache).
     */
    public boolean commandCapable(final ScreenType screenType, final ScreenConfig config) {
        final var service = screenServices.get(screenType);
        return service instanceof CommandScreenService<?> && config != null
                && uncheckedCommandCapable(service, config);
    }

    @SuppressWarnings("unchecked")
    private static boolean uncheckedCommandCapable(final ScreenService<? extends ScreenConfig> service,
                                                   final ScreenConfig config) {
        return ((CommandScreenService<ScreenConfig>) service).commandCapable(config);
    }

    /** Codec for this panel's next upload: RAW inside the v2 downgrade window, else PAL_RLE. */
    private int codecBitsFor(final String serial) {
        return animationLoadAckService.prefersRaw(serial) ? ANIM_CODEC_RAW : ANIM_CODEC_PAL_RLE;
    }

    private boolean upload(final String serial, final long frameDelayMs, final List<byte[]> payloads,
                           final long uploadId, final boolean stageOnly, final int slot,
                           final int codecBits, final String jobId) {
        log.debug("------> Uploading animation ({} frames, {} ms delay, slot {}, stageOnly={}, codec={}) to panel {}",
                payloads.size(), frameDelayMs, slot, stageOnly,
                codecBits == ANIM_CODEC_PAL_RLE ? "PAL_RLE" : "RAW", serial);

        final int flags = (stageOnly ? ANIM_FLAG_STAGE_ONLY : 0) | codecBits;
        publishAnim(serial + ANIM_START_TOPIC,
                animStartPayload(payloads.size(), (int) frameDelayMs, uploadId, flags, slot));

        // Full speed in the sense of no inter-frame pacing beyond the broker-ack window: the
        // panel buffers frames in RAM and drains them to flash at its own pace, and the
        // bounded in-flight window keeps at most MAX_ANIM_MESSAGES_IN_FLIGHT un-acked
        // messages towards the broker (the firmware relies on that MQTT flow control).
        // Safe point per frame: a superseded run (config save) stops mid-upload.
        for (int i = 0; i < payloads.size(); i++) {
            if (!rotationStateService.isAllowed(serial, jobId)) {
                log.debug("------> Superseded animation upload aborted for panel {}", serial);
                return false;
            }
            publishAnim(serial + ANIM_FRAME_TOPIC, animFramePayload(i, payloads.get(i), codecBits));
        }
        return true;
    }

    /** Publishes one {@code /anim/*} message inside the bounded in-flight window. */
    private void publishAnim(final String topic, final byte[] payload) {
        try {
            animMessagesInFlight.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the animation in-flight window", e);
        }
        try {
            mqttTransport.publishAsync(topic, payload, 1, false)
                    .whenComplete((result, throwable) -> animMessagesInFlight.release());
        } catch (RuntimeException e) {
            animMessagesInFlight.release();
            throw e;
        }
    }

    private FrameScreenService<FrameScreenConfig> frameService(final ScreenType screenType) {
        return (FrameScreenService<FrameScreenConfig>) screenServices.get(screenType);
    }

    private static long elapsedMillis(final long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    static Duration uploadAckTimeout(final int frameCount) {
        return Duration.ofMillis(Math.min(60_000, 5_000 + frameCount * 250L));
    }

    static byte[] animStartPayload(final int frameCount, final int frameDelayMs,
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

    static byte[] animPlayPayload(final long uploadId, final int slot) {
        return new byte[]{
                'A', 'N', 'I', 'P',
                (byte) slot,
                (byte) uploadId, (byte) (uploadId >> 8),
                (byte) (uploadId >> 16), (byte) (uploadId >> 24)
        };
    }

    static byte[] animFramePayload(final int frameIdx, final byte[] rgb565, final int codecBits) {
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
}
