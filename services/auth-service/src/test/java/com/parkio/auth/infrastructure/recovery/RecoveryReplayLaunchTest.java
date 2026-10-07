package com.parkio.auth.infrastructure.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.infrastructure.lifecycle.ErasureDurableRecordingWorker;
import com.parkio.auth.infrastructure.lifecycle.ErasureStuckGaugeJob;
import com.parkio.auth.infrastructure.lifecycle.RetentionCleanupJob;
import com.parkio.auth.infrastructure.messaging.ErasureAckKafkaConsumer;
import com.parkio.auth.infrastructure.messaging.ModerationActionsKafkaConsumer;
import com.parkio.auth.infrastructure.security.SecurityConfig;
import com.parkio.auth.infrastructure.web.GatewayAuthFilter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.mock.env.MockEnvironment;

/** How {@code main} reaches the recovery-replay command, and the command line it accepts. */
class RecoveryReplayLaunchTest {

    private static final String ATTEMPT = "5e5e5e5e-0000-4000-8000-00000000a771";
    private static final String[] OPTIONS = {
        "--evidence=/tmp/set.json", "--trust=/tmp/trust.json", "--attempt=" + ATTEMPT, "--dataset=stamp-1",
        "--target-identity=postgresql:1:parkio_auth", "--verdict-out=/tmp/verdict.json",
    };

    @Test
    void anOrdinaryStartIsNotARecoveryLaunch() {
        assertThat(RecoveryReplayLaunch.applies(new String[] {"--server.port=8081"}, Map.of(), new Properties()))
                .isFalse();
        assertThat(RecoveryReplayLaunch.applies(new String[0], Map.of("SPRING_PROFILES_ACTIVE", "prod"), new Properties()))
                .isFalse();
    }

    @Test
    void theProfileIsRecognisedFromArgumentsEnvironmentOrSystemProperties() {
        assertThat(RecoveryReplayLaunch.profileRequested(
                new String[] {"--spring.profiles.active=prod,recovery-replay"}, Map.of(), new Properties())).isTrue();
        assertThat(RecoveryReplayLaunch.profileRequested(
                new String[0], Map.of("SPRING_PROFILES_ACTIVE", "recovery-replay"), new Properties())).isTrue();
        Properties system = new Properties();
        system.setProperty("spring.profiles.active", " recovery-replay ");
        assertThat(RecoveryReplayLaunch.profileRequested(new String[0], Map.of(), system)).isTrue();
        assertThat(RecoveryReplayLaunch.profileRequested(
                new String[] {"--spring.profiles.active=recovery-replay-other"}, Map.of(), new Properties())).isFalse();
    }

    @Test
    void recoveryOptionsWithoutTheProfileAreRefusedBeforeSpringStarts() {
        assertThat(RecoveryReplayLaunch.applies(OPTIONS, Map.of(), new Properties())).isTrue();

        int code = RecoveryReplayLaunch.run(Object.class, OPTIONS, Map.of(), new Properties());

        assertThat(code).isEqualTo(RecoveryReplayExit.REFUSED.code());
    }

    @Test
    void theExitCodesAreDistinctAndOnlyCompleteIsZero() {
        assertThat(RecoveryReplayExit.values()).extracting(RecoveryReplayExit::code)
                .containsExactly(0, 20, 21, 22, 23, 24, 25, 26);
    }

    @Test
    void argumentsAreParsedStrictly() {
        RecoveryReplayArguments parsed = RecoveryReplayArguments.parse(OPTIONS);

        assertThat(parsed.attempt()).isEqualTo(UUID.fromString(ATTEMPT));
        assertThat(parsed.evidence()).isEqualTo(Path.of("/tmp/set.json"));
        assertThat(parsed.timeout()).isEqualTo(Duration.ofMinutes(15));
        assertThat(RecoveryReplayArguments.parse(with("--timeout-seconds=3600")).timeout()).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void springAndLoggingOptionsNeverReachTheCommandContext() {
        // Review B2: spring.main.web-application-type and friends could override .web(NONE).
        String reason = "Spring and logging options are not accepted; the command takes only its own options"
                + " (set the profile with SPRING_PROFILES_ACTIVE)";
        for (String option : List.of("--spring.main.web-application-type=servlet", "--spring.profiles.active=recovery-replay",
                "--spring.datasource.url=jdbc:postgresql://elsewhere/parkio_auth", "--logging.level.root=WARN")) {
            refused(with(option), reason);
        }
    }

    @Test
    void aSpringOptionIsRefusedBeforeSpringStarts() {
        String[] args = with("--spring.main.web-application-type=servlet");
        Map<String, String> env = Map.of("SPRING_PROFILES_ACTIVE", "recovery-replay");

        assertThat(RecoveryReplayLaunch.applies(args, env, new Properties())).isTrue();
        assertThat(RecoveryReplayLaunch.run(Object.class, args, env, new Properties()))
                .isEqualTo(RecoveryReplayExit.REFUSED.code());
    }

    @Test
    void theProfileOnTheCommandLineIsRoutedToTheLauncherAndRefusedThere() {
        // Never an ordinary service started with the recovery profile.
        String[] args = {"--spring.profiles.active=recovery-replay"};

        assertThat(RecoveryReplayLaunch.applies(args, Map.of(), new Properties())).isTrue();
        assertThat(RecoveryReplayLaunch.run(Object.class, args, Map.of(), new Properties()))
                .isEqualTo(RecoveryReplayExit.REFUSED.code());
    }

    @Test
    void anOrdinaryStartWithTheProfileActiveFromAnySourceIsRefusedBeforeAnyContext() {
        // Review N10: an include or a profile group reaches the profile without SPRING_PROFILES_ACTIVE.
        for (String[] args : List.of(
                new String[] {"--spring.profiles.include=recovery-replay"},
                new String[] {"--spring.profiles.active=prod", "--spring.profiles.group.prod=recovery-replay"})) {
            assertThat(RecoveryReplayLaunch.startOrdinary(Object.class, args)).as(String.join(" ", args)).hasValue(20);
        }
    }

    @Test
    void underTheProfileTheLiveComponentsAreNotInTheCommandContext() {
        for (Class<?> live : List.of(SecurityConfig.class, GatewayAuthFilter.class, RetentionCleanupJob.class,
                ErasureStuckGaugeJob.class, ErasureDurableRecordingWorker.class, ModerationActionsKafkaConsumer.class)) {
            assertThat(live.getAnnotation(Profile.class)).as(live.getSimpleName()).isNotNull();
            assertThat(live.getAnnotation(Profile.class).value()).as(live.getSimpleName()).containsExactly("!recovery-replay");
        }
    }

    @Test
    void theAckConsumerJoinsItsOwnGroupUnderTheProfile() throws Exception {
        String expression = ErasureAckKafkaConsumer.class
                .getMethod("onMessage", ConsumerRecord.class, String.class, Acknowledgment.class)
                .getAnnotation(KafkaListener.class).groupId();
        assertThat(expression).startsWith("#{").endsWith("}");
        String body = expression.substring(2, expression.length() - 1);
        Map<String, String> expected = Map.of(
                "recovery-replay", "parkio.auth.erasure.recovery-replay", "prod", "parkio.auth.erasure", "", "parkio.auth.erasure");
        expected.forEach((profile, group) -> {
            MockEnvironment environment = new MockEnvironment();
            if (!profile.isEmpty()) {
                environment.setActiveProfiles(profile);
            }
            Object resolved = new SpelExpressionParser().parseExpression(body)
                    .getValue(new StandardEvaluationContext(new BeanExpressionRoot(environment)));
            assertThat(resolved).as(profile).isEqualTo(group);
        });
    }

    /** Resolves {@code environment} as the listener's bean expression context does. */
    public static final class BeanExpressionRoot {
        private final MockEnvironment environment;

        BeanExpressionRoot(MockEnvironment environment) {
            this.environment = environment;
        }

        public MockEnvironment getEnvironment() {
            return environment;
        }
    }

    @Test
    void anythingElseIsRefused() {
        refused(with("--required-through-sequence=7"), "unknown option --required-through-sequence");
        refused(with("--receipt=r.json"), "unknown option --receipt");
        refused(with("--evidence=/tmp/other.json"), "option --evidence given twice");
        refused(with("positional"), "positional arguments are not accepted; use --name=value");
        refused(with("--dataset"), "options must be given as --name=value: --dataset");
        refused(with("--timeout-seconds=0"), "--timeout-seconds must be between 1 and 3600");
        refused(with("--timeout-seconds=3601"), "--timeout-seconds must be between 1 and 3600");
        refused(with("--timeout-seconds=soon"), "--timeout-seconds must be a whole number");
        refused(new String[] {OPTIONS[0], OPTIONS[1], OPTIONS[3], OPTIONS[4], OPTIONS[5]}, "--attempt is required");
        refused(new String[] {OPTIONS[0], OPTIONS[1], "--attempt=not-a-uuid", OPTIONS[3], OPTIONS[4], OPTIONS[5]},
                "--attempt must be a UUID");
        refused(new String[] {OPTIONS[0], OPTIONS[1], "--attempt=" + ATTEMPT.toUpperCase(), OPTIONS[3], OPTIONS[4],
                OPTIONS[5]}, "--attempt must be a lowercase UUID");
    }

    private static void refused(String[] args, String message) {
        assertThatThrownBy(() -> RecoveryReplayArguments.parse(args))
                .isInstanceOf(RecoveryReplayArguments.Refusal.class)
                .hasMessage(message);
    }

    private static String[] with(String... extra) {
        String[] all = new String[OPTIONS.length + extra.length];
        System.arraycopy(OPTIONS, 0, all, 0, OPTIONS.length);
        System.arraycopy(extra, 0, all, OPTIONS.length, extra.length);
        return all;
    }
}
