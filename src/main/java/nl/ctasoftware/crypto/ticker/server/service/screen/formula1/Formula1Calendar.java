package nl.ctasoftware.crypto.ticker.server.service.screen.formula1;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record Formula1Calendar(
        int season,
        List<Formula1Race> formula1Races
) {
    public record Formula1Race(
            int round,
            String name,
            String circuitName,
            String country,
            Instant date,
            Instant fp1,
            Instant fp2,
            Instant fp3,
            Instant qualifying,
            Instant sprintQualifying,
            Instant sprint
    ) {
    }

    public record NextSession(String sessionName, Instant dateTime) {}

    public Formula1Race getNextFormula1Race() {
        if (formula1Races == null || formula1Races.isEmpty()) return null;

        Instant now = Instant.now();
        final int RACE_DURATION_HOURS = 3; // simple heuristic

        return formula1Races.stream()
                .filter(r -> r != null && r.date() != null)
                .sorted(Comparator.comparing(Formula1Race::date)) // or by round(), up to you
                // "upcoming or current": now <= raceStart + ~3h
                .filter(r -> !now.isAfter(r.date().plus(RACE_DURATION_HOURS, ChronoUnit.HOURS)))
                .findFirst()
                .orElse(null);
    }

    public NextSession getNextSession() {
        if (formula1Races == null || formula1Races.isEmpty()) return null;

        Instant now = Instant.now();

        return formula1Races.stream()
                .flatMap(this::sessionsOf)                 // all sessions of a race
                .filter(ns -> ns.dateTime() != null)
                .filter(ns -> !ns.dateTime().isBefore(now)) // >= now (treat exact-now as upcoming)
                .min(Comparator.comparing(NextSession::dateTime))
                .orElse(null);
    }

    private Stream<NextSession> sessionsOf(Formula1Race r) {
        return Stream.of(
                new NextSession("FP1", r.fp1()),
                new NextSession("FP2", r.fp2()),
                new NextSession("FP3", r.fp3()),
                new NextSession("Sprint Qualifying", r.sprintQualifying()),
                new NextSession("Sprint", r.sprint()),
                new NextSession("Qualifying", r.qualifying()),
                new NextSession("Race", r.date())
        );
    }

    public List<Formula1Race> getNextFormula1Races(int x) {
        if (x <= 0 || formula1Races == null || formula1Races.isEmpty()) return List.of();

        Instant now = Instant.now();
        final int RACE_DURATION_HOURS = 3;

        return formula1Races.stream()
                .filter(Objects::nonNull)
                .filter(r -> r.date() != null)
                .filter(r -> !now.isAfter(r.date().plus(RACE_DURATION_HOURS, ChronoUnit.HOURS)))
                .sorted(Comparator.comparing(Formula1Race::date))
                .limit(x)
                .toList();
    }

}
