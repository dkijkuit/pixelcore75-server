package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.Px75CustomScreen;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.dto.CustomScreenDto;
import nl.ctasoftware.crypto.ticker.server.model.dto.SaveCustomScreenRequest;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.repository.CustomScreenRepository;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The per-user custom screen library: CRUD with ownership/sharing rules, render-time
 * reference resolution ({@link CustomScreenResolver}) for the panel rotation loop, and the
 * save-time validation gate for panel configs referencing library entries.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomScreenLibraryService implements CustomScreenResolver {

    private final CustomScreenRepository customScreenRepository;
    private final CustomScreenService customScreenService;
    private final PanelConfigRepository panelConfigRepository;
    private final Px75UserDetailsService userDetailsService;

    /* ------------------------------------------------------------------
     * Library queries
     * ------------------------------------------------------------------ */

    /** Everything the requesting user may see: their own screens, others' shared ones (admin: all). */
    public List<CustomScreenDto> listVisible(final Px75User user) {
        final boolean admin = user.getRoles().contains(Px75Role.ADMIN);
        final List<Px75CustomScreen> entries = new ArrayList<>(customScreenRepository
                .findByUserIdOrderByUpdatedAtDesc(user.getId()));
        if (admin) {
            customScreenRepository.findAll().forEach(entry -> {
                if (entry.getUserId() != user.getId()) {
                    entries.add(entry);
                }
            });
        } else {
            entries.addAll(customScreenRepository.findBySharedTrueAndUserIdNotOrderByUpdatedAtDesc(user.getId()));
        }
        entries.sort(Comparator.comparing(Px75CustomScreen::getUpdatedAt).reversed());

        final Map<Long, String> ownerNames = ownerNames(entries);
        return entries.stream()
                .map(entry -> CustomScreenDto.summary(entry, entry.getUserId() == user.getId(),
                        ownerNames.get(entry.getUserId())))
                .toList();
    }

    /** Full detail (design + usage count); visible = owner, shared by its owner, or admin. */
    public CustomScreenDto getVisible(final Px75User user, final long id) {
        final Px75CustomScreen entry = requireVisible(user, id);
        return CustomScreenDto.detail(entry, entry.getUserId() == user.getId(),
                ownerNames(List.of(entry)).get(entry.getUserId()), usageCount(id));
    }

    /* ------------------------------------------------------------------
     * CRUD
     * ------------------------------------------------------------------ */

    public CustomScreenDto create(final Px75User user, final SaveCustomScreenRequest request) {
        final ValidatedDesign validated = validate(request);
        final Px75CustomScreen saved = customScreenRepository.save(new Px75CustomScreen(null, user.getId(),
                validated.design().name(), request.durationSeconds(), request.design(), request.shared(),
                validated.thumbnail(), Instant.now()));
        return CustomScreenDto.detail(saved, true, user.getUsername(), 0);
    }

    public CustomScreenDto update(final Px75User user, final long id, final SaveCustomScreenRequest request) {
        final Px75CustomScreen entry = customScreenRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Custom screen " + id + " not found"));
        if (entry.getUserId() != user.getId() && !user.getRoles().contains(Px75Role.ADMIN)) {
            throw new IllegalArgumentException("Custom screen " + id + " belongs to another user");
        }
        final ValidatedDesign validated = validate(request);
        entry.setName(validated.design().name());
        entry.setDurationSeconds(request.durationSeconds());
        entry.setDesign(request.design());
        entry.setShared(request.shared());
        entry.setThumbnail(validated.thumbnail());
        entry.setUpdatedAt(Instant.now());
        final Px75CustomScreen saved = customScreenRepository.save(entry);
        return CustomScreenDto.detail(saved, saved.getUserId() == user.getId(),
                ownerNames(List.of(saved)).get(saved.getUserId()), usageCount(id));
    }

    /**
     * Deleting leaves references in panel rotations dangling; the rotation loop skips those
     * entries (logged once per cycle) until the panel config is saved without them.
     */
    public void delete(final Px75User user, final long id) {
        final Px75CustomScreen entry = customScreenRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Custom screen " + id + " not found"));
        if (entry.getUserId() != user.getId() && !user.getRoles().contains(Px75Role.ADMIN)) {
            throw new IllegalArgumentException("Custom screen " + id + " belongs to another user");
        }
        customScreenRepository.delete(entry);
    }

    /* ------------------------------------------------------------------
     * Reference resolution (render path — no access checks: a rotation entry
     * that referenced a shared screen must keep rendering even if the owner
     * later unshares it, until the panel config is saved again)
     * ------------------------------------------------------------------ */

    @Override
    public CustomScreenConfig hydrate(final CustomScreenConfig config) {
        if (config.design() != null) {
            return config; // inline legacy entry, nothing to resolve
        }
        return customScreenRepository.findById(config.customScreenId())
                .map(entry -> new CustomScreenConfig(ScreenType.CUSTOM, config.durationSeconds(),
                        entry.getDesign()))
                .orElse(null);
    }

    /* ------------------------------------------------------------------
     * Save-time validation for panel configs
     * ------------------------------------------------------------------ */

    /**
     * Validates every CUSTOM entry of a rotation about to be saved: inline designs are
     * dry-compiled (unchanged legacy behavior), library references must exist, be visible
     * to the saving user (owner, shared, or admin), and their current design must compile.
     */
    public void validateRotation(final Px75User user, final List<? extends ScreenConfig> configs) {
        for (final ScreenConfig config : configs) {
            if (!(config instanceof CustomScreenConfig custom)) {
                continue;
            }
            if (custom.customScreenId() != null) {
                final Px75CustomScreen entry = customScreenRepository.findById(custom.customScreenId())
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Custom screen " + custom.customScreenId() + " does not exist"));
                if (entry.getUserId() != user.getId() && !entry.isShared()
                        && !user.getRoles().contains(Px75Role.ADMIN)) {
                    throw new IllegalArgumentException(
                            "Custom screen " + entry.getCustomScreenId() + " is not shared by its owner");
                }
            }
            final CustomScreenConfig hydrated = hydrate(custom);
            if (hydrated == null) {
                throw new IllegalArgumentException(
                        "Custom screen " + custom.customScreenId() + " does not exist");
            }
            customScreenService.renderScreen(hydrated); // dry-compile the design
        }
    }

    /** Number of panel rotations referencing this screen (delete/summary warning). */
    public long usageCount(final long id) {
        return panelConfigRepository.countContaining("[{\"customScreenId\":" + id + "}]");
    }

    /* ------------------------------------------------------------------
     * Internals
     * ------------------------------------------------------------------ */

    private ValidatedDesign validate(final SaveCustomScreenRequest request) {
        if (request.design() == null || request.design().isBlank()) {
            throw new IllegalArgumentException("design is required");
        }
        if (request.durationSeconds() < 1) {
            throw new IllegalArgumentException("durationSeconds must be >= 1");
        }
        final PxdDesign design = PxdDesign.parse(request.design()); // full validation, 400 on invalid
        final FrameScreenService.FrameStream preview = customScreenService.renderPreview(request.design());
        return new ValidatedDesign(design, toPngDataUrl(preview.frames().getFirst()));
    }

    private record ValidatedDesign(PxdDesign design, String thumbnail) {
    }

    private Px75CustomScreen requireVisible(final Px75User user, final long id) {
        final Px75CustomScreen entry = customScreenRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Custom screen " + id + " not found"));
        if (entry.getUserId() != user.getId() && !entry.isShared()
                && !user.getRoles().contains(Px75Role.ADMIN)) {
            throw new IllegalArgumentException("Custom screen " + id + " not found");
        }
        return entry;
    }

    private Map<Long, String> ownerNames(final List<Px75CustomScreen> entries) {
        return entries.stream()
                .map(Px75CustomScreen::getUserId)
                .distinct()
                .collect(Collectors.toMap(Function.identity(),
                        userId -> userDetailsService.getPx75UserById(userId).getUsername()));
    }

    private static String toPngDataUrl(final BufferedImage image) {
        try (final ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
