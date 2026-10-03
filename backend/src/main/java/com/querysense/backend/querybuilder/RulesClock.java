package com.querysense.backend.querybuilder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Today's date for the rule engine, in the configured zone, optionally pinned for tests and demos. */
@Component
public class RulesClock {

    private final Clock clock;

    @Autowired
    public RulesClock(@Value("${querysense.rules.time-zone:Asia/Kolkata}") String zone,
                      @Value("${querysense.rules.today:}") String today) {
        ZoneId id = ZoneId.of(zone);
        this.clock = today == null || today.isBlank()
                ? Clock.system(id)
                : Clock.fixed(LocalDate.parse(today).atStartOfDay(id).toInstant(), id);
    }

    private RulesClock(Clock clock) {
        this.clock = clock;
    }

    public static RulesClock fixed(LocalDate today, ZoneId zone) {
        return new RulesClock(Clock.fixed(today.atStartOfDay(zone).toInstant(), zone));
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }
}
