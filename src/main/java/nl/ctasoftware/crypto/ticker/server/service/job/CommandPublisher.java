package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.mqtt.MqttTransport;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The ACMD command path (plan §6), extracted from the old {@code PanelScreenJob}: builds
 * the batch/refresh streams (with the frame-path fallback decided by the caller), publishes
 * every batch to {@code <serial>/cmd} QoS 0 under the serial's epoch monitor (a stale
 * page-flip thread can never land an old page after a newer render), clears the retained
 * base image, and hands the SSE preview to {@link PreviewStreamer}.
 */
@Slf4j
@Service
public class CommandPublisher {

    static final String CMD_TOPIC = "/cmd";

    private final MqttTransport mqttTransport;
    private final RotationStateService rotationStateService;
    private final PreviewStreamer previewStreamer;

    public CommandPublisher(final MqttTransport mqttTransport,
                            final RotationStateService rotationStateService,
                            final PreviewStreamer previewStreamer) {
        this.mqttTransport = mqttTransport;
        this.rotationStateService = rotationStateService;
        this.previewStreamer = previewStreamer;
    }

    /** Builds the batch stream, or null (logged) when the command rendering fails — caller falls back. */
    public CommandScreenService.BatchStream buildBatches(
            final CommandScreenService<ScreenConfig> screenService, final ScreenConfig screenConfig, final long panelId) {
        try {
            return screenService.renderCommandBatches(screenConfig);
        } catch (final Exception e) {
            log.error("Command batch build failed for screen {} on panel {}; using the frame path",
                    screenConfig.screenType(), panelId, e);
            return null;
        }
    }

    /**
     * Builds the live-refresh stream (whose eager first render validates it), or null
     * when the screen offers no refresh or that first render fails — caller continues
     * with the page batches, then the frame path, exactly like a batch build failure.
     */
    public CommandScreenService.RefreshStream buildRefresh(
            final CommandScreenService<ScreenConfig> screenService, final ScreenConfig screenConfig, final long panelId) {
        try {
            final CommandScreenService.RefreshStream refresh =
                    screenService.renderCommandRefresh(screenConfig);
            return refresh != null && refresh.refreshMs() > 0 ? refresh : null;
        } catch (final Exception e) {
            log.error("Command refresh build failed for screen {} on panel {}; using the batch path",
                    screenConfig.screenType(), panelId, e);
            return null;
        }
    }

    /**
     * Live-refreshing command screens: like {@link #renderCommandScreen} (retained
     * base cleared, QoS-0 fire-and-forget batch, slot counted from the send, SSE
     * preview from the {@link AcmdMirror}), but two virtual threads run the slot:
     * the refresh loop re-renders and republishes on the refresh grid, and the
     * refreshing preview samples whatever mirror is current on the slot-anchored
     * timeline (each refresh interval is a whole parametric loop by contract, so the
     * sweep phase stays continuous across swaps).
     */
    public void renderRefreshingCommandScreen(final CommandScreenService.RefreshStream refresh,
                                              final String serial, final long epoch,
                                              final ScreenConfig screenConfig) {
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();
        try {
            mqttTransport.publish(serial, new byte[0], 1, true);
            final byte[] firstBatch = refresh.firstBatch();
            final long sendNanos = System.nanoTime();
            publishCommandBatch(serial, epoch, firstBatch);
            log.info("------> Command batch ({} bytes, refreshing every {} ms) sent to panel {} for screen {}",
                    firstBatch.length, refresh.refreshMs(), serial, screenConfig.screenType());

            final AtomicReference<AcmdMirror> mirrorRef = new AtomicReference<>(AcmdMirror.parse(firstBatch));

            final var baseImage = AcmdMirror.toBufferedImage(mirrorRef.get().frameAt(0));
            previewStreamer.archivePng(serial, baseImage);
            previewStreamer.scaleImageAndPublish(serial, baseImage);
            final long deadlineNanos = sendNanos + slotNanos;
            Thread.startVirtualThread(() ->
                    previewStreamer.streamRefreshingCommandPreview(serial, epoch, mirrorRef, sendNanos, deadlineNanos));
            Thread.startVirtualThread(() ->
                    refreshCommandBatches(serial, epoch, refresh, mirrorRef, sendNanos, deadlineNanos));
        } catch (Exception e) {
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
    private void refreshCommandBatches(final String serial, final long epoch,
                                       final CommandScreenService.RefreshStream refresh,
                                       final AtomicReference<AcmdMirror> mirrorRef,
                                       final long sendNanos, final long deadlineNanos) {
        final long refreshNanos = refresh.refreshMs() * 1_000_000L;
        long nextPublishNanos = sendNanos + refreshNanos;
        while (epoch == rotationStateService.currentEpoch(serial)
                && !Thread.currentThread().isInterrupted()
                && System.nanoTime() < deadlineNanos) {
            byte[] nextBatch = null;
            try {
                nextBatch = refresh.nextBatches().get();
            } catch (final Exception e) {
                log.error("Command refresh render failed for panel {}; keeping the last batch on display", serial, e);
            }
            sleepUntil(nextPublishNanos);
            // Advance the grid past everything the render consumed: a publish that
            // cannot land on time is dropped to the next grid point, never late.
            do {
                nextPublishNanos += refreshNanos;
            } while (nextPublishNanos <= System.nanoTime());

            if (epoch != rotationStateService.currentEpoch(serial)
                    || Thread.currentThread().isInterrupted()
                    || System.nanoTime() >= deadlineNanos) {
                return;
            }
            if (nextBatch == null) {
                continue; // render failed this interval; the panel keeps the previous batch
            }
            try {
                final AcmdMirror mirror = AcmdMirror.parse(nextBatch);
                publishCommandBatch(serial, epoch, nextBatch);
                mirrorRef.set(mirror);
                log.debug("------> Command refresh ({} bytes) sent to panel {}", nextBatch.length, serial);
            } catch (final Exception e) {
                log.error("Failed to publish command refresh for panel {}", serial, e);
                return;
            }
        }
    }

    /**
     * ACMD v1 command path: publishes the first fully framed batch to
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
    public void renderCommandScreen(final CommandScreenService.BatchStream stream,
                                    final String serial, final long epoch, final ScreenConfig screenConfig) {
        final long slotNanos = Duration.ofSeconds(screenConfig.durationSeconds()).toNanos();
        try {
            mqttTransport.publish(serial, new byte[0], 1, true);
            final byte[] firstBatch = stream.batches().getFirst();
            final long sendNanos = System.nanoTime();
            publishCommandBatch(serial, epoch, firstBatch);
            log.info("------> Command batch ({} bytes, {} pages) sent to panel {} for screen {}",
                    firstBatch.length, stream.batches().size(), serial, screenConfig.screenType());

            final List<AcmdMirror> mirrors = new ArrayList<>(stream.batches().size());
            for (final byte[] pageBatch : stream.batches()) {
                mirrors.add(AcmdMirror.parse(pageBatch));
            }

            final var baseImage = AcmdMirror.toBufferedImage(mirrors.getFirst().frameAt(0));
            previewStreamer.archivePng(serial, baseImage);
            previewStreamer.scaleImageAndPublish(serial, baseImage);
            final long deadlineNanos = sendNanos + slotNanos;
            if (stream.batches().size() == 1 || stream.pageDwellMs() <= 0) {
                final AcmdMirror mirror = mirrors.getFirst();
                Thread.startVirtualThread(() ->
                        previewStreamer.streamCommandPreview(serial, epoch, mirror, sendNanos, deadlineNanos));
            } else {
                Thread.startVirtualThread(() ->
                        cycleCommandPages(serial, epoch, stream, mirrors, sendNanos, deadlineNanos));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Publishes one command batch to {@code <serial>/cmd} while holding the serial's
     * epoch monitor, re-checking the epoch inside it: a newer render (rotation boundary,
     * config save) bumps the epoch before its own batch publish, so a stale page-flip
     * thread can never land an old page after that — at worst its page lands just
     * before it and the wire order keeps the new batch final.
     */
    public void publishCommandBatch(final String serial, final long epoch, final byte[] batch) {
        final RotationStateService.RotationState state = rotationStateService.stateFor(serial);
        synchronized (state) {
            if (epoch != state.epoch.get()) {
                return; // superseded while waiting for the monitor
            }
            mqttTransport.publish(serial + CMD_TOPIC, batch, 0, false);
        }
    }

    /**
     * Page-cycling command screens: flips {@code <serial>/cmd} to the next batch at
     * each {@code pageDwellMs} boundary (each page's parametrics restart at its flip,
     * exactly what the panel does on a fresh batch) and drives the SSE preview from
     * that page's mirror on the same virtual timeline. Mirrors the frame path's
     * page-stream semantics: one pass through the pages, the last page holds until the
     * slot ends. Stops like the single-batch preview: superseded by a newer render,
     * slot end, or interruption.
     */
    private void cycleCommandPages(final String serial, final long epoch,
                                   final CommandScreenService.BatchStream stream,
                                   final List<AcmdMirror> mirrors, final long sendNanos,
                                   final long deadlineNanos) {
        final List<byte[]> batches = stream.batches();
        final long pageDwellMs = stream.pageDwellMs();
        int currentPage = 0;
        while (epoch == rotationStateService.currentEpoch(serial)
                && System.nanoTime() < deadlineNanos) {
            final long elapsedMs = Math.max(0, (System.nanoTime() - sendNanos) / 1_000_000);
            final int pageIndex = (int) Math.min(elapsedMs / pageDwellMs, (long) (batches.size() - 1));
            if (pageIndex != currentPage) {
                currentPage = pageIndex;
                final byte[] pageBatch = batches.get(currentPage);
                try {
                    publishCommandBatch(serial, epoch, pageBatch);
                    log.debug("------> Command page {} ({} bytes) sent to panel {}",
                            currentPage, pageBatch.length, serial);
                } catch (Exception e) {
                    log.error("Failed to publish command page for panel {}", serial, e);
                }
            }
            try {
                previewStreamer.scaleImageAndPublish(serial, AcmdMirror.toBufferedImage(
                        mirrors.get(currentPage).frameAt(elapsedMs - currentPage * pageDwellMs)));
            } catch (Exception e) {
                log.error("Failed to broadcast command preview frame for panel {}", serial, e);
                return;
            }
            try {
                Thread.sleep(PreviewStreamer.COMMAND_PREVIEW_TICK_MS);
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
}
