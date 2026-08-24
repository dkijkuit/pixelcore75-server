package nl.ctasoftware.crypto.ticker.server.controller;

import nl.ctasoftware.crypto.ticker.server.exception.ApiErrorHandler;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.dto.CustomScreenDto;
import nl.ctasoftware.crypto.ticker.server.model.dto.SaveCustomScreenRequest;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenLibraryService;
import nl.ctasoftware.crypto.ticker.server.service.screen.custom.CustomScreenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Preview endpoint (spec §5.9) and the library CRUD endpoints on a standalone MockMvc: no
 * security filter chain, the {@code ApiErrorHandler} advice supplies the 400-with-message
 * contract for invalid designs; {@code @AuthenticationPrincipal} resolves from the
 * SecurityContextHolder via the custom argument resolver.
 */
class CustomScreenControllerTests {

    private MockMvc mockMvc;
    private CustomScreenLibraryService libraryService;
    private Px75User user;

    @BeforeEach
    void setUp() {
        final CustomScreenService service = new CustomScreenService(
                new PaintToolsService(font("assets/fonts/Habbo.ttf", 16f),
                        font("assets/fonts/MiniLine2.ttf", 8f),
                        font("assets/fonts/EXEPixelPerfect.ttf", 16f)),
                font("assets/fonts/cg-pixel-4x5.ttf", 5f),
                font("assets/fonts/MiniLine2.ttf", 8f),
                font("assets/fonts/Habbo.ttf", 16f),
                font("assets/fonts/EXEPixelPerfect.ttf", 16f),
                font("assets/fonts/TinyUnicode.ttf", 16f),
                font("assets/fonts/frostfont-logo.ttf", 7f),
                font("assets/fonts/grinched-4x7.ttf", 9f));
        libraryService = Mockito.mock(CustomScreenLibraryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new CustomScreenController(service, libraryService))
                .setControllerAdvice(new ApiErrorHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();

        user = new Px75User("alice", "pw", "alice@example.com", Set.of(Px75Role.USER));
        user.setId(1L);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user, null));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void previewReturnsFramesAndDelay() throws Exception {
        final String design = """
                {"schemaVersion":1,"name":"Preview","frameDelayMs":150,"frames":[
                  {"layers":[{"type":"rect","x":0,"y":0,"w":8,"h":8,"color":"#FF0000","filled":true}]},
                  {"layers":[{"type":"rect","x":0,"y":0,"w":8,"h":8,"color":"#0000FF","filled":true}]}
                ]}""";

        final MvcResult result = mockMvc.perform(post("/v1/screen/custom/preview")
                        .contentType("application/json")
                        .content("{\"design\":" + jsonString(design) + "}"))
                .andExpect(status().isOk())
                .andReturn();

        final String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"frameDelayMs\":150"), body);
        assertEquals(2, countOccurrences(body, "data:image/png;base64,"), body);
    }

    @Test
    void previewAllowsSingleFrame() throws Exception {
        final String design = "{\"schemaVersion\":1,\"name\":\"Single\",\"frames\":[{\"layers\":[]}]}";

        final MvcResult result = mockMvc.perform(post("/v1/screen/custom/preview")
                        .contentType("application/json")
                        .content("{\"design\":" + jsonString(design) + "}"))
                .andExpect(status().isOk())
                .andReturn();

        final String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"frameDelayMs\":100"), body);
        assertEquals(1, countOccurrences(body, "data:image/png;base64,"), body);
    }

    @Test
    void previewRejectsInvalidDesignWith400AndMessage() throws Exception {
        mockMvc.perform(post("/v1/screen/custom/preview")
                        .contentType("application/json")
                        .content("{\"design\":" + jsonString("{\"schemaVersion\":3,\"name\":\"X\",\"frames\":[]}") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("\"message\":\"Unsupported schemaVersion 3, expected 1 or 2\"")));
    }

    @Test
    void previewSamplesParametricDesignsOnTheCommandTick() throws Exception {
        final String design = """
                {"schemaVersion":2,"name":"Sweep","frames":[{"layers":[
                  {"type":"sweep","cx":32,"cy":16,"r":15,"color":"#00FF00","speedDegPerSec":45}
                ]}]}""";

        final MvcResult result = mockMvc.perform(post("/v1/screen/custom/preview")
                        .contentType("application/json")
                        .content("{\"design\":" + jsonString(design) + "}"))
                .andExpect(status().isOk())
                .andReturn();

        final String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"frameDelayMs\":100"), body);
        // sweep loop 360/45 s = 8000 ms → 80 sampled frames at the 100 ms tick
        assertEquals(80, countOccurrences(body, "data:image/png;base64,"), body);
    }

    /* ------------------------- library endpoints ------------------------- */

    @Test
    void listDelegatesToLibraryService() throws Exception {
        Mockito.when(libraryService.listVisible(user)).thenReturn(List.of(summaryDto()));

        mockMvc.perform(get("/v1/screen/custom"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("\"name\":\"Blinkenlights\"")));
    }

    @Test
    void createDelegatesToLibraryService() throws Exception {
        final String design = "{\"schemaVersion\":1,\"name\":\"Test\",\"frames\":[{\"layers\":[]}]}";
        Mockito.when(libraryService.create(eq(user), any(SaveCustomScreenRequest.class)))
                .thenReturn(summaryDto());

        mockMvc.perform(post("/v1/screen/custom")
                        .contentType("application/json")
                        .content("{\"design\":" + jsonString(design) + ",\"durationSeconds\":12,\"shared\":true}"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsString()
                        .contains("\"shared\":true")));

        Mockito.verify(libraryService).create(eq(user), Mockito.argThat(req ->
                req.durationSeconds() == 12 && req.shared() && req.design().contains("\"Test\"")));
    }

    @Test
    void updateDelegatesToLibraryService() throws Exception {
        Mockito.when(libraryService.update(eq(user), eq(5L), any(SaveCustomScreenRequest.class)))
                .thenReturn(summaryDto());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/v1/screen/custom/5")
                        .contentType("application/json")
                        .content("{\"design\":\"{}\",\"durationSeconds\":10,\"shared\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    void deleteReturnsNoContent() throws Exception {
        mockMvc.perform(delete("/v1/screen/custom/5"))
                .andExpect(status().isNoContent());

        Mockito.verify(libraryService).delete(user, 5L);
    }

    @Test
    void detailIncludesDesignAndUsageCount() throws Exception {
        Mockito.when(libraryService.getVisible(user, 5L)).thenReturn(new CustomScreenDto(5, "Blinkenlights",
                12, true, true, 1, "alice", null, Instant.parse("2026-01-01T00:00:00Z"),
                "{\"schemaVersion\":1}", 3L));

        final MvcResult result = mockMvc.perform(get("/v1/screen/custom/5"))
                .andExpect(status().isOk())
                .andReturn();

        final String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("\"usageCount\":3"), body);
        assertTrue(body.contains("\"design\":\"{\\\"schemaVersion\\\":1}\""), body);
    }

    private static CustomScreenDto summaryDto() {
        return new CustomScreenDto(5, "Blinkenlights", 12, true, true, 1, "alice", "data:image/png;base64,AAA",
                Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    private static String jsonString(final String raw) {
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static int countOccurrences(final String haystack, final String needle) {
        int count = 0;
        for (int idx = haystack.indexOf(needle); idx != -1; idx = haystack.indexOf(needle, idx + needle.length())) {
            count++;
        }
        return count;
    }

    private static Font font(final String file, final float size) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new File(file)).deriveFont(size);
        } catch (final FontFormatException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
