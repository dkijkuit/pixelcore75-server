package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.panel.AnimationLoadAckService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenResolver;
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
 * The rotation loop's CUSTOM library-reference handling, docker-free against a mocked
 * MQTT client: references are re-resolved every cycle (library edits propagate live),
 * dangling references are skipped instead of failing the panel, and a rotation whose
 * entries are all dangling shows the no-config image but keeps retrying so it recovers
 * once the library entry exists again.
 */
class PanelScreenJobCustomHydrationTests {

    private static final String SERIAL = "CUSTOMJOB1";

    /** Single-frame design: static path (retained base image), no ANIM machinery needed. */
    private static final String STATIC_DESIGN =
            "{\"schemaVersion\":1,\"name\":\"Static\",\"frames\":[{\"layers\":[]}]}";

    private record Pub(String topic, byte[] payload, boolean retained) {}

    private IMqttClient mqttClient;
    private ImageService imageService;
    private final List<Pub> pubs = new CopyOnWriteArrayList<>();

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
    void skipsDanglingReferenceAndRendersTheRemainingScreen() throws Exception {
        // rotation: [dangling reference, resolvable reference] — only the second renders
        final PanelScreenJob job = newJob(
                List.of(reference(42L, 10), reference(7L, 5)),
                config -> config.customScreenId() == 42L ? null : resolved(config));

        final Optional<Duration> next = job.run();

        assertTrue(next.isPresent());
        assertEquals(Duration.ofSeconds(5), next.get()); // the resolved screen's duration
        assertEquals(1, baseTopicPubs().size());
        assertTrue(baseTopicPubs().get(0).payload().length > 0, "static screen publishes a retained frame");
    }

    @Test
    void allReferencesDanglingShowsNoConfigAndKeepsRetrying() throws Exception {
        final PanelScreenJob job = newJob(List.of(reference(42L, 10)),
                config -> null);

        final Optional<Duration> next = job.run();

        assertTrue(next.isPresent());
        assertEquals(Duration.ofSeconds(30), next.get(), "re-checks the library instead of stopping forever");
        assertEquals(1, baseTopicPubs().size());
    }

    @Test
    void referenceIsReResolvedEveryCycle() throws Exception {
        final AtomicInteger resolutions = new AtomicInteger();
        final PanelScreenJob job = newJob(List.of(reference(7L, 1)),
                config -> {
                    resolutions.incrementAndGet();
                    return resolved(config);
                });

        job.run();
        job.run();

        assertEquals(2, resolutions.get(), "library edits must reach a running rotation without a re-save");
    }

    /* ------------------------------ fixtures ------------------------------ */

    private static CustomScreenConfig reference(final long id, final int durationSeconds) {
        return new CustomScreenConfig(ScreenType.CUSTOM, durationSeconds, id, null);
    }

    private static CustomScreenConfig resolved(final CustomScreenConfig config) {
        return new CustomScreenConfig(ScreenType.CUSTOM, config.durationSeconds(), STATIC_DESIGN);
    }

    private PanelScreenJob newJob(final List<nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig> configs,
                                  final CustomScreenResolver resolver) throws org.eclipse.paho.client.mqttv3.MqttException {
        final Px75Panel px75Panel = mock(Px75Panel.class);
        when(px75Panel.getSerial()).thenReturn(SERIAL);
        final ConcurrentMap<String, AtomicInteger> previewGenerations = new ConcurrentHashMap<>();
        final ScreenService<CustomScreenConfig> customService = new StubCustomService();
        return new PanelScreenJob(px75Panel, new Px75PanelConfig(1L, configs),
                List.of(customService), imageService, mqttClient, mock(ImageBroadcasterService.class),
                new AnimationLoadAckService(mqttClient), previewGenerations, false, resolver);
    }

    private List<Pub> baseTopicPubs() {
        return pubs.stream().filter(p -> p.topic().equals(SERIAL)).toList();
    }

    /** Extends the concrete service (the static-path switch casts to it); renders fixed frames. */
    private static final class StubCustomService
            extends nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService {
        StubCustomService() {
            super(new nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService(null, null, null),
                    null, null, null, null, null, null, null);
        }

        @Override
        public Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
            return Optional.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }

        @Override
        public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
            return List.of(new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB));
        }
    }
}
