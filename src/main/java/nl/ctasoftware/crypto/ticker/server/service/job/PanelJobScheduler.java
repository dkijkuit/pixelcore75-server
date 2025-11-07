package nl.ctasoftware.crypto.ticker.server.service.job;

import java.io.IOException;

public interface PanelJobScheduler {
    void schedulePanelScreenJob(long panelId, long userId);

    void scheduleStartup() throws IOException;
}
