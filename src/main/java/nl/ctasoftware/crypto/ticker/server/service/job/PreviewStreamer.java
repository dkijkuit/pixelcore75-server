package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Everything SSE-preview, extracted from the old {@code PanelScreenJob}: the animation,
 * command and refreshing-command preview loops plus the scaled frame hand-off to the
 * broadcaster. Every loop stops on the three supersession signals — a newer run's epoch,
 * the slot deadline, or interruption.
 */
@Slf4j
@Service
public class PreviewStreamer {

    /** SSE preview tick for command screens (ms): smooth for the radar sweep speeds, cheap otherwise. */
    static final long COMMAND_PREVIEW_TICK_MS = 100;

    private final ImageService imageService;
    private final ImageBroadcasterService imageBroadcasterService;
    private final RotationStateService rotationStateService;

    /**
     * Perf (plan §7): the {@code generated_images/<serial>.png} archive is written on a
     * virtual thread, never on the publish path, and skipped entirely when the bytes are
     * unchanged (a crypto price refresh must not re-encode an identical PNG every cycle).
     */
    private final ConcurrentMap<String, byte[]> lastArchivedPng = new ConcurrentHashMap<>();

    public PreviewStreamer(final ImageService imageService,
                           final ImageBroadcasterService imageBroadcasterService,
                           final RotationStateService rotationStateService) {
        this.imageService = imageService;
        this.imageBroadcasterService = imageBroadcasterService;
        this.rotationStateService = rotationStateService;
    }

    public void scaleImageAndPublish(final String serial, final BufferedImage image) throws IOException {
        final BufferedImage scaledImage = imageService.scale(image, 640, 320);
        imageBroadcasterService.updateLatest(serial, scaledImage);
    }

    public void archivePng(final String serial, final BufferedImage image) {
        Thread.startVirtualThread(() -> {
            try (var baos = new ByteArrayOutputStream()) {
                ImageIO.write(image, "PNG", baos);
                final byte[] png = baos.toByteArray();
                final byte[] previous = lastArchivedPng.put(serial, png);
                if (Arrays.equals(previous, png)) {
                    return; // content dedup: unchanged screen, skip the disk write
                }
                final File target = new File("generated_images/" + serial + ".png");
                final File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    log.warn("Could not create generated_images directory; skipping archive write");
                    return;
                }
                ImageIO.write(image, "PNG", target);
            } catch (IOException e) {
                log.warn("Failed to archive preview PNG for panel {}", serial, e);
            }
        });
    }

    public void forgetArchivedPng(final String serial) {
        lastArchivedPng.remove(serial);
    }

    /**
     * Streams the animation frames to the SSE live preview at the configured frame delay for
     * the screen's time slot. Runs on its own virtual thread and stops early once superseded
     * by a newer render (epoch bump), when the slot ends, or when interrupted.
     */
    public void streamAnimationPreview(final String serial, final long epoch, final java.util.List<BufferedImage> frames,
                                       final long frameDelayMs, final long deadlineNanos) {
        int i = 1;
        while (rotationStateService.currentEpoch(serial) == epoch
                && System.nanoTime() < deadlineNanos) {
            try {
                scaleImageAndPublish(serial, frames.get(i % frames.size()));
            } catch (IOException e) {
                log.error("Failed to broadcast animation preview frame for panel {}", serial, e);
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

    /**
     * Streams the SSE preview of a command screen from the {@link AcmdMirror} at a fixed
     * tick on the slot's virtual timeline (elapsed since the batch send), mirroring
     * {@link #streamAnimationPreview}: stops early once superseded by a newer render
     * (epoch bump), when the slot ends, or when interrupted.
     */
    public void streamCommandPreview(final String serial, final long epoch, final AcmdMirror mirror,
                                     final long sendNanos, final long deadlineNanos) {
        while (rotationStateService.currentEpoch(serial) == epoch
                && System.nanoTime() < deadlineNanos) {
            try {
                final long elapsedMs = Math.max(0, (System.nanoTime() - sendNanos) / 1_000_000);
                scaleImageAndPublish(serial, AcmdMirror.toBufferedImage(mirror.frameAt(elapsedMs)));
            } catch (IOException e) {
                log.error("Failed to broadcast command preview frame for panel {}", serial, e);
                return;
            }
            sleepTick();
        }
    }

    /**
     * Refreshing variant of {@link #streamCommandPreview}: samples the latest mirror
     * (swapped at each refresh publish) but keeps the slot-anchored timeline — each
     * refresh interval is a whole parametric loop by contract, so the sweep phase is
     * continuous across swaps and the elapsed time never resets.
     */
    public void streamRefreshingCommandPreview(final String serial, final long epoch,
                                               final java.util.concurrent.atomic.AtomicReference<AcmdMirror> mirrorRef,
                                               final long sendNanos, final long deadlineNanos) {
        while (rotationStateService.currentEpoch(serial) == epoch
                && System.nanoTime() < deadlineNanos) {
            try {
                final long elapsedMs = Math.max(0, (System.nanoTime() - sendNanos) / 1_000_000);
                scaleImageAndPublish(serial, AcmdMirror.toBufferedImage(mirrorRef.get().frameAt(elapsedMs)));
            } catch (IOException e) {
                log.error("Failed to broadcast command preview frame for panel {}", serial, e);
                return;
            }
            sleepTick();
        }
    }

    private static void sleepTick() {
        try {
            Thread.sleep(COMMAND_PREVIEW_TICK_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
