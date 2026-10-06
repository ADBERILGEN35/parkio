package com.parkio.auth.infrastructure.recovery;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The recovery-replay command line. Only {@code --name=value} options from {@link #OPTIONS} are
 * accepted, each once; Spring's own {@code --spring.*} and {@code --logging.*} options pass through.
 * There is no cutoff or receipt option: no offline-verifiable receipt exists, so coverage is
 * reported only through the verified sequence.
 *
 * @param evidence the trusted-set file the isolated restore wrote (embeds the evidence bundle)
 * @param trust the trust document (pinned database identity and producer keys)
 * @param attempt the recovery attempt id
 * @param dataset the restored dataset id
 * @param targetIdentity the isolated target's database identity, from the #121 ticket
 * @param timeout the bounded wait for acknowledgements
 * @param verdictOut where the verdict JSON is written
 */
public record RecoveryReplayArguments(Path evidence,
                                      Path trust,
                                      UUID attempt,
                                      String dataset,
                                      String targetIdentity,
                                      Duration timeout,
                                      Path verdictOut) {

    public static final List<String> OPTIONS = List.of(
            "evidence", "trust", "attempt", "dataset", "target-identity", "timeout-seconds", "verdict-out");
    static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(15);
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(60);

    /** Thrown for any argument the command does not accept; the command exits {@code REFUSED}. */
    public static final class Refusal extends RuntimeException {
        public Refusal(String message) {
            super(message);
        }
    }

    public static RecoveryReplayArguments parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--spring.") || arg.startsWith("--logging.")) {
                continue;
            }
            int equals = arg.indexOf('=');
            if (!arg.startsWith("--")) {
                throw new Refusal("positional arguments are not accepted; use --name=value");
            }
            if (equals < 0) {
                throw new Refusal("options must be given as --name=value: " + arg);
            }
            String name = arg.substring(2, equals);
            if (!OPTIONS.contains(name)) {
                throw new Refusal("unknown option --" + name);
            }
            if (values.put(name, arg.substring(equals + 1)) != null) {
                throw new Refusal("option --" + name + " given twice");
            }
        }
        UUID attempt;
        try {
            attempt = UUID.fromString(required(values, "attempt"));
        } catch (IllegalArgumentException ex) {
            throw new Refusal("--attempt must be a UUID");
        }
        if (!attempt.toString().equals(values.get("attempt"))) {
            throw new Refusal("--attempt must be a lowercase UUID");
        }
        return new RecoveryReplayArguments(
                Path.of(required(values, "evidence")),
                Path.of(required(values, "trust")),
                attempt,
                required(values, "dataset"),
                required(values, "target-identity"),
                timeout(values.get("timeout-seconds")),
                Path.of(required(values, "verdict-out")));
    }

    private static Duration timeout(String value) {
        if (value == null) {
            return DEFAULT_TIMEOUT;
        }
        long seconds;
        try {
            seconds = Long.parseLong(value);
        } catch (NumberFormatException ex) {
            throw new Refusal("--timeout-seconds must be a whole number");
        }
        if (seconds < 1 || seconds > MAX_TIMEOUT.toSeconds()) {
            throw new Refusal("--timeout-seconds must be between 1 and " + MAX_TIMEOUT.toSeconds());
        }
        return Duration.ofSeconds(seconds);
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            throw new Refusal("--" + name + " is required");
        }
        return value;
    }
}
