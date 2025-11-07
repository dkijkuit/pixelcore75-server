package nl.ctasoftware.crypto.ticker.server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelService;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

@Slf4j
@EnableCaching
@EnableScheduling
@SpringBootApplication
@RequiredArgsConstructor
public class CryptoTickerServerApplication {
	final Px75UserDetailsService userDetailsService;
	final Px75PanelService panelService;
	final Px75PanelConfigService panelConfigService;
	public static void main(String[] args) {
		SpringApplication.run(CryptoTickerServerApplication.class, args);
	}

	@EventListener(ApplicationReadyEvent.class)
	public void startup() {
		try{
            //SoccerMatch soccerMatch = espnSoccerMatchClient.getSoccerMatch("ned.2", "4426");
            userDetailsService.loadUserByUsername("admin");
		} catch (UsernameNotFoundException unfe) {
			//generateRandomPanels(1);
			Px75User admin = userDetailsService.addUser(new Px75User("admin", "test", "admin@adminspace.com", Set.of(Px75Role.ADMIN, Px75Role.USER)));
//			Px75User jaap = userDetailsService.addUser(new Px75User("jaap", "test", "jaap@adminspace.com", Set.of(Px75Role.USER)));
//			Px75User dirk = userDetailsService.addUser(new Px75User("dirk", "test", "dirk@adminspace.com", Set.of(Px75Role.USER)));
//			Px75User jan = userDetailsService.addUser(new Px75User("jan", "test", "jan@adminspace.com", Set.of(Px75Role.USER)));
//			Px75User ziko = userDetailsService.addUser(new Px75User("ziko", "test", "ziko@adminspace.com", Set.of(Px75Role.USER)));
//
//			panelService.addPx75Panel(new Px75Panel(null, admin.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Crypto ticker panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, admin.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Stock ticker panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, jaap.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Generic text panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, jaap.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Sports score panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, jaap.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Weather panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, dirk.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "SMS message panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, dirk.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Stock ticker panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, jan.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Crypto ticker panel", Px75PanelType.P_64_X_32));
//			panelService.addPx75Panel(new Px75Panel(null, ziko.getId(), "PXCORE75-"+randomMACAddress().replace(":", ""), randomMACAddress(), "Forecast panel", Px75PanelType.P_64_X_32));
		}
	}

	void generateRandomPanels(int amount) {
		final Px75User admin = userDetailsService.addUser(new Px75User("admin", "test", "admin@adminspace.com", Set.of(Px75Role.ADMIN, Px75Role.USER)));

		Faker faker = new Faker();
		for (int i = 0; i < amount; i++) {
			String name = faker.internet().username();
			String email = faker.internet().emailAddress();
			String macAddress = faker.internet().macAddress();
			String panelName = faker.word().noun() + " panel";
			Px75User user = userDetailsService.addUser(new Px75User(name, "test", email, Set.of(Px75Role.USER)));
			Px75Panel px75Panel = panelService.addPx75Panel(new Px75Panel(null, user.getId(), "PXCORE75-" + macAddress.replace(":", ""), macAddress, panelName, Px75PanelType.P_64_X_32));

			int panelScreenConfigsCount = ThreadLocalRandom.current().nextInt(0, 5);
			Px75PanelConfig panelConfig = new Px75PanelConfig();
			log.info("Adding {} pane configs", panelScreenConfigsCount);
			List<ImageScreenConfig> imageScreenConfigs = IntStream.range(0, panelScreenConfigsCount).mapToObj(c -> {
				String image = switch(c){
                    case 1 -> "assets/images/landscape.png";
					case 2 -> "assets/images/moon.png";
					case 3 -> "assets/images/seas.png";
					default -> "assets/images/cat.png";
				};

				return new ImageScreenConfig(ScreenType.IMAGE, 5, image, null);
			}).toList();
			panelConfig.setPanelId(px75Panel.getPanelId());
			panelConfig.setScreensConfig(imageScreenConfigs);

			panelConfigService.save(panelConfig);
		}
	}
}
