package com.parkio.auth.infrastructure.recovery;

import java.util.Arrays;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.boot.web.server.WebServerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.Ordered;

/**
 * How {@code main} runs the recovery-replay command (owner decision P6; PR #295 review B1, B2, B5).
 *
 * <ol>
 *   <li>Recovery options without the {@value #PROFILE} profile are refused before Spring starts,
 *       so they can never start an ordinary auth instance. The profile comes from
 *       {@code SPRING_PROFILES_ACTIVE} (or {@code -Dspring.profiles.active}); the command line takes
 *       only the command's own options, so no {@code --spring.*} option reaches Spring.</li>
 *   <li>{@link RecoveryReplayPreflight} runs every check that can refuse once the environment is
 *       prepared and before any application context exists. A refusal ends the run there:
 *       nothing is migrated, written, subscribed or scheduled.</li>
 *   <li>Only then is the command context started: a plain {@link AnnotationConfigApplicationContext},
 *       whatever {@code spring.main.web-application-type} says, so it can never start a web server.
 *       Under the profile the web security configuration, the retention job, the other schedulers
 *       and the live moderation consumer are not part of it; the ACK consumer joins its own
 *       recovery group. Flyway, if the restored schema is older, migrates here, after the checks.
 *       The run is refused if a web server context or factory exists anyway.</li>
 *   <li>{@link RecoveryReplayCommand} runs once and the process exits with its code.</li>
 * </ol>
 */
public final class RecoveryReplayLaunch {

    public static final String PROFILE = "recovery-replay";

    private RecoveryReplayLaunch() {
    }

    /** Whether {@code main} must hand the arguments to {@link #run} instead of starting the service. */
    public static boolean applies(String[] args, Map<String, String> env, Properties system) {
        return profileRequested(args, env, system) || recoveryOptionPresent(args);
    }

    /**
     * Whether the profile is requested anywhere, the command line included: a
     * {@code --spring.profiles.active=recovery-replay} argument must reach {@link #run} (and be
     * refused there), not start an ordinary service with the profile.
     */
    static boolean profileRequested(String[] args, Map<String, String> env, Properties system) {
        String fromArgs = Arrays.stream(args)
                .filter(arg -> arg.startsWith("--spring.profiles.active="))
                .map(arg -> arg.substring("--spring.profiles.active=".length()))
                .reduce("", (left, right) -> left + "," + right);
        return names(fromArgs) || names(system.getProperty("spring.profiles.active"))
                || names(env.get("SPRING_PROFILES_ACTIVE"));
    }

    static boolean recoveryOptionPresent(String[] args) {
        return Arrays.stream(args).anyMatch(arg -> RecoveryReplayArguments.OPTIONS.stream()
                .anyMatch(option -> arg.equals("--" + option) || arg.startsWith("--" + option + "=")));
    }

    private static boolean names(String profiles) {
        return profiles != null && Arrays.stream(profiles.split(",")).map(String::trim).anyMatch(PROFILE::equals);
    }

    /** Runs the command once and returns the process exit code. */
    public static int run(Class<?> application, String[] args, Map<String, String> env, Properties system) {
        return run(application, args, env, system, RecoveryReplayPreflight.forDatasource());
    }

    static int run(Class<?> application, String[] args, Map<String, String> env, Properties system,
                   RecoveryReplayPreflight preflight) {
        if (!profileRequested(args, env, system)) {
            return refused("recovery options need the '" + PROFILE + "' profile");
        }
        RecoveryReplayArguments arguments;
        try {
            arguments = RecoveryReplayArguments.parse(args);
        } catch (RecoveryReplayArguments.Refusal refusal) {
            return refused(refusal.getMessage());
        }
        RecoveryReplayVerdict verdict = new RecoveryReplayVerdict(arguments);
        Preflight listener = new Preflight(preflight, arguments, verdict);
        AtomicReference<RecoveryReplayConnectionGuard> guard = new AtomicReference<>();
        SpringApplication app = new SpringApplicationBuilder(application)
                .web(WebApplicationType.NONE)
                .contextFactory(ApplicationContextFactory.ofContextClass(AnnotationConfigApplicationContext.class))
                .profiles(PROFILE)
                .listeners(listener)
                // Runs only once the preflight accepted the run: it holds the checked identity.
                .initializers(context -> {
                    guard.set(new RecoveryReplayConnectionGuard(listener.plan.get().connectedIdentity()));
                    guard.get().install(context);
                })
                .build();
        ConfigurableApplicationContext context;
        try {
            // No command line: the arguments are the command's own, never Spring properties.
            context = app.run();
        } catch (RuntimeException ex) {
            RecoveryReplayRefusal refusal = listener.refusal.get();
            if (refusal == null && guard.get() != null) {
                refusal = guard.get().refusal();
            }
            if (refusal != null) {
                verdict.put("reason", refusal.getMessage());
                System.err.println("recovery replay refused: " + refusal.getMessage());
                return verdict.finish(refusal.exit(), arguments.verdictOut()).code();
            }
            verdict.put("reason", "the command context could not start: " + ex.getClass().getSimpleName());
            System.err.println("recovery replay could not start: " + ex.getClass().getSimpleName());
            return verdict.finish(RecoveryReplayExit.INTERNAL, arguments.verdictOut()).code();
        }
        RecoveryReplayExit exit;
        if (guard.get().refusal() != null) {
            verdict.put("reason", guard.get().refusal().getMessage());
            exit = verdict.finish(guard.get().refusal().exit(), arguments.verdictOut());
        } else if (context instanceof WebServerApplicationContext
                || context.getBeanNamesForType(WebServerFactory.class, true, false).length > 0) {
            verdict.put("reason", "a web server context or factory was created; the command never serves traffic");
            exit = verdict.finish(RecoveryReplayExit.INTERNAL, arguments.verdictOut());
        } else {
            try {
                exit = context.getBean(RecoveryReplayCommand.class).execute(listener.plan.get(), verdict);
            } catch (RuntimeException ex) {
                verdict.put("reason", "internal failure: " + ex.getClass().getSimpleName());
                exit = verdict.finish(RecoveryReplayExit.INTERNAL, arguments.verdictOut());
            }
            // A connection refused during the run taints it, whatever the command concluded.
            RecoveryReplayRefusal refusedDuringRun = guard.get().refusal();
            if (refusedDuringRun != null && exit != refusedDuringRun.exit()) {
                verdict.put("reason", refusedDuringRun.getMessage());
                exit = verdict.finish(refusedDuringRun.exit(), arguments.verdictOut());
            }
        }
        int code = exit.code();
        return SpringApplication.exit(context, () -> code);
    }

    /**
     * The ordinary service start (PR #295 review N10). The recovery-replay profile, from whatever
     * source activates it ({@code SPRING_PROFILES_INCLUDE}, a lower-case variable, a config file,
     * a profile group), never reaches an ordinary start: it would drop the web security
     * configuration and move the ACK consumer to the recovery group. Refused (20) once the
     * environment is prepared, before any context exists. Returns the exit code when refused.
     */
    public static OptionalInt startOrdinary(Class<?> application, String[] args) {
        SpringApplication app = new SpringApplication(application);
        app.addListeners(new OrdinaryStartGuard());
        try {
            app.run(args);
            return OptionalInt.empty();
        } catch (SpringApplication.AbandonedRunException ex) {
            return OptionalInt.of(RecoveryReplayExit.REFUSED.code());
        }
    }

    /** Abandons an ordinary start whose prepared environment has the recovery-replay profile. */
    static final class OrdinaryStartGuard implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            if (event.getEnvironment().matchesProfiles(PROFILE)) {
                System.err.println("auth-service refused to start: the '" + PROFILE + "' profile is active; it is"
                        + " only for the one-shot recovery-replay command (SPRING_PROFILES_ACTIVE with its options)");
                throw new SpringApplication.AbandonedRunException();
            }
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    private static int refused(String reason) {
        System.err.println("recovery replay refused: " + reason);
        return RecoveryReplayExit.REFUSED.code();
    }

    /** Runs the preflight on the prepared environment; a refusal abandons the run before any context exists. */
    private static final class Preflight implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        private final RecoveryReplayPreflight preflight;
        private final RecoveryReplayArguments arguments;
        private final RecoveryReplayVerdict verdict;
        private final AtomicReference<RecoveryReplayPreflight.Plan> plan = new AtomicReference<>();
        private final AtomicReference<RecoveryReplayRefusal> refusal = new AtomicReference<>();

        Preflight(RecoveryReplayPreflight preflight, RecoveryReplayArguments arguments, RecoveryReplayVerdict verdict) {
            this.preflight = preflight;
            this.arguments = arguments;
            this.verdict = verdict;
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            try {
                plan.set(preflight.check(event.getEnvironment(), arguments, verdict));
            } catch (RecoveryReplayRefusal refused) {
                refusal.set(refused);
                throw new SpringApplication.AbandonedRunException();
            } catch (RuntimeException ex) {
                refusal.set(new RecoveryReplayRefusal(RecoveryReplayExit.INTERNAL,
                        "the preflight failed: " + ex.getClass().getSimpleName()));
                throw new SpringApplication.AbandonedRunException();
            }
        }

        @Override
        public int getOrder() {
            // After the config data and logging listeners: the environment is complete.
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
