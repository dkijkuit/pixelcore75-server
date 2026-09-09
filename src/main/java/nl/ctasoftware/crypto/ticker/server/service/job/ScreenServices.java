package nl.ctasoftware.crypto.ticker.server.service.job;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The screen services indexed by their type, built once from the Spring context. */
@Service
public class ScreenServices {

    private final Map<ScreenType, ScreenService<? extends ScreenConfig>> byType;

    public ScreenServices(final List<ScreenService<? extends ScreenConfig>> screenServices) {
        this.byType = screenServices.stream()
                .collect(Collectors.toUnmodifiableMap(ScreenService::getScreenType, Function.identity()));
    }

    public ScreenService<? extends ScreenConfig> get(final ScreenType type) {
        return byType.get(type);
    }
}
