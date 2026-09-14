package nl.ctasoftware.crypto.ticker.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Hermetic full-context boot (plan §6): Testcontainers Postgres (Flyway baselines + migrates
 * it, JobRunr creates its tables, Hibernate validates) and no MQTT requirement — the HiveMQ
 * transport connects asynchronously and logs instead of failing when no broker is reachable.
 * The old contract (live compose Postgres + NanoMQ required for tests) is gone.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "jobrunr.dashboard.enabled=false")
class CryptoTickerServerApplicationTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17.6")
			.withDatabaseName("pixelcore75")
			.withUsername("pixelcore75")
			.withPassword("pixelcore75");

	@Test
	void contextLoads() {
	}
}
