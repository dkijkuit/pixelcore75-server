package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The rotation loop's disabled-screen handling, docker-free against a mocked MQTT
 * client: {@code disabled} entries stay in the config but are skipped by rendering,
 * and a rotation whose entries are all disabled shows the no-config image without
 * re-scheduling (same observable behavior as a panel without any config).
 */
class PanelScreenJobDisabledTests {

    private static final String SERIAL = "DISABLEDJOB1";

    /** Single-frame design: static path (retained base image), no ANIM machinery needed. */
    private static final String STATIC_DESIGN =
            "{\"schemaVersion\":1,\"name\":\"Static\",\"frames\":[{\"layers\":[]}]}";

    private record Pub(String topic, byte[] payload, boolean retained) {}

    private IMqttClient mqttClient;
    private ImageService imageService;
    private final List<Pub> pubs = new CopyOnWriteArrayList<>();
    private final List<Integer> renderedDurations = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void createScratchDir() throws Exception {
        Files.createDirectories(Path.of("generated_images"));
    }

    @AfterAll
    static void deleteScratchFile() throws Exception {
        Files.deleteIfExists(Path.of("generated_images", SERIAL + ".png"));
    }

    @BeforeEach
    void setUp() throws Exception {
        mqttClient = mock(IMqttClient.class);
        // AnimationLoadAckService subscribes on construction; a bare mock client is enough here
        doAnswer(invocation -> {
            final MqttMessage message = invocation.getArgument(1);
            pubs.add(new Pub(invocation.getArgument(0), message.getPayload(), message.isRetained()));
            return null;
        }).when(mqttClient).publish(anyString(), any(MqttMessage.class));
        doAnswer(invocation -> {
            pubs.add(new Pub(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(3)));
            return null;
        }).when(mqttClient).publish(anyString(), any(byte[].class), anyInt(), anyBoolean());

        imageService = mock(ImageService.class);
        when(imageService.scale(any(), anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        when(imageService.bufferedImageToBytes(any(), eq(0), eq(0))).thenReturn(new byte[4096]);
        when(imageService.imageToBufferedImage(anyString()))
                .thenReturn(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
    }

    @Test
    void disabledScreensAreSkippedByTheRotation() throws Exception {
        final PanelScreenJob job = newJob(List.of(inline(10, true), inline(5, false)));

        for (int cycle = 0; cycle < 3; cycle++) {
            final Optional<Duration> next = job.run();
            assertTrue(next.isPresent());
            assertEquals(Duration.ofSeconds(5), next.get(), "only the enabled screen's slot counts");
        }

        assertEquals(List.of(5, 5, 5), renderedDurations, "only the enabled screen ever renders");
        assertEquals(3, baseTopicPubs().size(), "every cycle publishes the rendered static frame");
    }

    @Test
    void allDisabledBehavesLikeNoConfig() throws Exception {
        final PanelScreenJob job = newJob(List.of(inline(10, true), inline(20, true)));

        final Optional<Duration> next = job.run();

        assertTrue(next.isEmpty(), "nothing to schedule while every screen is disabled");
        assertTrue(renderedDurations.isEmpty(), "no screen service may render");
        assertEquals(1, baseTopicPubs().size(), "the no-config image is published instead");
        assertTrue(baseTopicPubs().get(0).retained());
        assertTrue(baseTopicPubs().get(0).payload().length > 0);
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static CustomScreenConfig inline(final int durationSeconds, final boolean disabled) {
        return new CustomScreenConfig(ScreenType.CUSTOM, durationSeconds, null, STATIC_DESIGN, disabled);
    }

    private PanelScreenJob newJob(final List<ScreenConfig> configs)
            throws org.eclipse.paho.client.mqttv3.MqttException {
        final Px75Panel px75Panel = mock(Px75Panel.class);
        when(px75Panel.getSerial()).thenReturn(SERIAL);
        final ConcurrentMap<String, AtomicInteger> previewGenerations = new ConcurrentHashMap<>();
        return new PanelScreenJob(px75Panel, new Px75PanelConfig(1L, configs),
                List.of(new RecordingCustomService()), imageService, mqttClient,
                mock(ImageBroadcasterService.class), new AnimationLoadAckService(mqttClient),
                previewGenerations, false);
    }

    private List<Pub> baseTopicPubs() {
        return pubs.stream().filter(p -> p.topic().equals(SERIAL)).toList();
    }

    /** Extends the concrete service (the static-path switch casts to it); records what renders. */
    private final class RecordingCustomService
            extends nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService {
        RecordingCustomService() {
            super(new nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService(null, null, null),
                    null, null, null, null, null, null, null);
        }

        @Override
        public Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
            renderedDurations.add(screenConfig.durationSeconds());
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }
    }
}
