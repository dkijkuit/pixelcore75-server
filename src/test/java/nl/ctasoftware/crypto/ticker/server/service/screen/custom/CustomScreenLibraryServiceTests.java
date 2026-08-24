package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import nl.ctasoftware.crypto.ticker.server.model.Px75CustomScreen;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.dto.CustomScreenDto;
import nl.ctasoftware.crypto.ticker.server.model.dto.SaveCustomScreenRequest;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.repository.CustomScreenRepository;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Library semantics with a mocked repository and a real (font-backed)
 * {@link CustomScreenService} so save-time dry-compiles/thumbnails behave like production.
 */
class CustomScreenLibraryServiceTests {

    private static final String VALID_DESIGN =
            "{\"schemaVersion\":1,\"name\":\"Blinkenlights\",\"frames\":[{\"layers\":[]}]}";

    private CustomScreenRepository repository;
    private PanelConfigRepository panelConfigRepository;
    private Px75UserDetailsService userDetailsService;
    private CustomScreenLibraryService library;

    private final Px75User alice = user(1L, "alice", Px75Role.USER);
    private final Px75User bob = user(2L, "bob", Px75Role.USER);
    private final Px75User admin = user(3L, "root", Px75Role.ADMIN);

    @BeforeEach
    void setUp() {
        repository = mock(CustomScreenRepository.class);
        panelConfigRepository = mock(PanelConfigRepository.class);
        userDetailsService = mock(Px75UserDetailsService.class);
        library = new CustomScreenLibraryService(repository, customScreenService(), panelConfigRepository,
                userDetailsService);

        when(userDetailsService.getPx75UserById(1L)).thenReturn(alice);
        when(userDetailsService.getPx75UserById(2L)).thenReturn(bob);
        when(userDetailsService.getPx75UserById(3L)).thenReturn(admin);
    }

    /* ----------------------------- CRUD ----------------------------- */

    @Test
    void createValidatesDesignAndStoresDerivedFields() {
        when(repository.save(any())).thenAnswer(inv -> {
            final Px75CustomScreen entry = inv.getArgument(0);
            entry.setCustomScreenId(99L); // what IDENTITY generation does
            return entry;
        });

        final CustomScreenDto dto = library.create(alice,
                new SaveCustomScreenRequest(VALID_DESIGN, 12, true));

        final ArgumentCaptor<Px75CustomScreen> captor = ArgumentCaptor.forClass(Px75CustomScreen.class);
        verify(repository).save(captor.capture());
        final Px75CustomScreen saved = captor.getValue();

        assertEquals("Blinkenlights", saved.getName()); // derived from the design
        assertEquals(12, saved.getDurationSeconds());
        assertTrue(saved.isShared());
        assertNotNull(saved.getThumbnail());
        assertTrue(saved.getThumbnail().startsWith("data:image/png;base64,"));
        assertEquals(1L, saved.getUserId());
        assertTrue(dto.owned());
    }

    @Test
    void createRejectsInvalidDesign() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                library.create(alice, new SaveCustomScreenRequest(
                        "{\"schemaVersion\":3,\"name\":\"X\",\"frames\":[]}", 10, false)));
        assertTrue(e.getMessage().contains("schemaVersion"));
    }

    @Test
    void createRejectsNonPositiveDuration() {
        assertThrows(IllegalArgumentException.class, () ->
                library.create(alice, new SaveCustomScreenRequest(VALID_DESIGN, 0, false)));
    }

    @Test
    void updateByNonOwnerFails() {
        when(repository.findById(5L)).thenReturn(Optional.of(entry(5L, 1L, false)));

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                library.update(bob, 5L, new SaveCustomScreenRequest(VALID_DESIGN, 10, false)));
        assertTrue(e.getMessage().contains("another user"));
        verify(repository, never()).save(any());
    }

    @Test
    void updateByAdminWorksForAnyEntry() {
        when(repository.findById(5L)).thenReturn(Optional.of(entry(5L, 1L, false)));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(panelConfigRepository.countContaining(any())).thenReturn(0L);
        final CustomScreenDto dto = library.update(admin, 5L,
                new SaveCustomScreenRequest(VALID_DESIGN, 9, true));
        assertEquals(9, dto.durationSeconds());
        assertTrue(dto.shared());
        assertFalse(dto.owned());
    }

    @Test
    void deleteByOwnerRemovesEntry() {
        final Px75CustomScreen entry = entry(5L, 1L, false);
        when(repository.findById(5L)).thenReturn(Optional.of(entry));

        library.delete(alice, 5L);
        verify(repository).delete(entry);
    }

    /* --------------------------- visibility --------------------------- */

    @Test
    void listShowsMineAndOthersSharedOnly() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(1L)).thenReturn(List.of(entry(5L, 1L, false)));
        when(repository.findBySharedTrueAndUserIdNotOrderByUpdatedAtDesc(1L))
                .thenReturn(List.of(entry(7L, 2L, true)));

        final List<CustomScreenDto> visible = library.listVisible(alice);

        assertEquals(2, visible.size());
        assertTrue(visible.stream().anyMatch(dto -> dto.id() == 5L && dto.owned()));
        assertTrue(visible.stream().anyMatch(dto -> dto.id() == 7L && !dto.owned() && dto.shared()));
    }

    @Test
    void adminSeesEverything() {
        when(repository.findByUserIdOrderByUpdatedAtDesc(3L)).thenReturn(List.of(entry(9L, 3L, false)));
        when(repository.findAll()).thenReturn(List.of(entry(5L, 1L, false), entry(7L, 2L, true), entry(9L, 3L, false)));

        assertEquals(3, library.listVisible(admin).size());
    }

    @Test
    void detailHidesPrivateEntriesOfOthers() {
        when(repository.findById(7L)).thenReturn(Optional.of(entry(7L, 2L, false)));

        assertThrows(IllegalArgumentException.class, () -> library.getVisible(alice, 7L));
    }

    @Test
    void detailIncludesUsageCountForSharedEntry() {
        when(repository.findById(7L)).thenReturn(Optional.of(entry(7L, 2L, true)));
        when(panelConfigRepository.countContaining(any())).thenReturn(4L);

        final CustomScreenDto dto = library.getVisible(alice, 7L);
        assertEquals(4L, dto.usageCount());
        assertEquals(VALID_DESIGN, dto.design());
    }

    /* --------------------------- hydration --------------------------- */

    @Test
    void hydrateResolvesReferenceToCurrentDesign() {
        when(repository.findById(7L)).thenReturn(Optional.of(entry(7L, 2L, true)));
        final CustomScreenConfig reference = new CustomScreenConfig(ScreenType.CUSTOM, 10, 7L, null);

        final CustomScreenConfig hydrated = library.hydrate(reference);

        assertNotNull(hydrated);
        assertNull(hydrated.customScreenId()); // hydrated copy is the inline shape
        assertEquals(VALID_DESIGN, hydrated.design());
        assertEquals(10, hydrated.durationSeconds()); // duration stays the rotation's own
    }

    @Test
    void hydrateReturnsNullForDanglingReference() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertNull(library.hydrate(new CustomScreenConfig(ScreenType.CUSTOM, 10, 42L, null)));
    }

    @Test
    void hydratePassesInlineEntriesThrough() {
        final CustomScreenConfig inline = new CustomScreenConfig(ScreenType.CUSTOM, 10, VALID_DESIGN);
        assertSame(inline, library.hydrate(inline));
    }

    /* --------------------- rotation validation --------------------- */

    @Test
    void validateRotationRejectsMissingReference() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                library.validateRotation(alice, List.of(new CustomScreenConfig(ScreenType.CUSTOM, 10, 42L, null))));
        assertTrue(e.getMessage().contains("does not exist"));
    }

    @Test
    void validateRotationRejectsUnsharedForeignScreen() {
        when(repository.findById(7L)).thenReturn(Optional.of(entry(7L, 2L, false)));

        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                library.validateRotation(alice, List.of(new CustomScreenConfig(ScreenType.CUSTOM, 10, 7L, null))));
        assertTrue(e.getMessage().contains("not shared"));
    }

    @Test
    void validateRotationAcceptsSharedForeignScreenAndCompilesIt() {
        when(repository.findById(7L)).thenReturn(Optional.of(entry(7L, 2L, true)));

        library.validateRotation(alice, List.of(new CustomScreenConfig(ScreenType.CUSTOM, 10, 7L, null)));
        // no exception: exists, shared, design compiles
    }

    @Test
    void validateRotationStillDryCompilesInlineDesigns() {
        assertThrows(IllegalArgumentException.class, () -> library.validateRotation(alice,
                List.of(new CustomScreenConfig(ScreenType.CUSTOM, 10,
                        "{\"schemaVersion\":1,\"name\":\"X\",\"frames\":[]}"))));
    }

    /* ----------------------------- helpers ----------------------------- */

    private Px75CustomScreen entry(final long id, final long ownerId, final boolean shared) {
        return new Px75CustomScreen(id, ownerId, "Blinkenlights", 10, VALID_DESIGN, shared,
                "data:image/png;base64,AAA", Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static Px75User user(final long id, final String username, final Px75Role role) {
        final Px75User user = new Px75User(username, "pw", username + "@example.com", Set.of(role));
        user.setId(id);
        return user;
    }

    private static CustomScreenService customScreenService() {
        return new CustomScreenService(
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
    }

    private static Font font(final String file, final float size) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new File(file)).deriveFont(size);
        } catch (final FontFormatException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
