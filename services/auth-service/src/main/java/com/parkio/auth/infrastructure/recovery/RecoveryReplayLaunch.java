package com.parkio.auth.infrastructure.recovery;

import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * How {@code main} runs the recovery-replay command. With the {@value #PROFILE} profile the service
 * starts without a web server ({@link WebApplicationType#NONE}: no HTTP listener), runs
 * {@link RecoveryReplayCommand} once and exits with its code. Recovery options without the profile
 * are refused before Spring starts, so they can never start an ordinary auth instance.
 */
public final class RecoveryReplayLaunch {

    public static final String PROFILE = "recovery-replay";

    private RecoveryReplayLaunch() {
    }

    /** Whether {@code main} must hand the arguments to {@link #run} instead of starting the service. */
    public static boolean applies(String[] args, Map<String, String> env, Properties system) {
        return profileRequested(args, env, system) || recoveryOptionPresent(args);
    }

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
        if (!profileRequested(args, env, system)) {
            System.err.println("recovery replay refused: recovery options need the '" + PROFILE + "' profile");
            return RecoveryReplayExit.REFUSED.code();
        }
        ConfigurableApplicationContext context;
        try {
            context = new SpringApplicationBuilder(application).web(WebApplicationType.NONE).run(args);
        } catch (RuntimeException ex) {
            System.err.println("recovery replay could not start: " + ex.getClass().getSimpleName());
            return RecoveryReplayExit.INTERNAL.code();
        }
        int code;
        try {
            code = context.getBean(RecoveryReplayCommand.class).execute(args).exitCode();
        } catch (RuntimeException ex) {
            code = RecoveryReplayExit.INTERNAL.code();
        }
        int exit = code;
        return SpringApplication.exit(context, () -> exit);
    }
}
