package com.parkio.parking.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.FieldError;

/**
 * #637: the #615 timing rules hold for every municipal source that is fetched over HTTP, not only İZUM.
 * Each of these clients reads the body through {@code BoundedFeedResponses}, only until
 * {@code max-response-time} after the request starts. So startup refuses a connect plus read timeout
 * that reaches that bound, because a first byte that arrives in time could not be used. It also refuses
 * a connect or read timeout under 1 ms, which the HTTP client treats as no timeout. Konya and Kayseri
 * broke the first rule with their defaults (5s + 30s against 30s). The other sources have no HTTP client
 * and so no timeout to check: the OSM import reads a local file, İZELMAN imports CSV files from an
 * operator directory ({@link IzelmanProperties}), and the fake test provider fetches nothing.
 */
class MunicipalFeedTimingBoundsValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(MunicipalFeedTimingBoundsValidationTest::withoutTheTestJvmEnvironment)
            .withUserConfiguration(PropertiesConfig.class);

    @EnableConfigurationProperties(MunicipalSourceProperties.class)
    static class PropertiesConfig {
    }

    /**
     * Boot validates a nested object only when it binds at least one of its properties, as it does in
     * production from {@code application.yml}. Binding {@code enabled} makes the check run on the Java
     * defaults; without a property, this test would pass whatever the defaults were.
     */
    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void theJavaDefaultsStart(String source) {
        runner.withPropertyValues("parkio.municipal." + source + ".enabled=true")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /** application.yml binds settings of every feed, so startup validates each of them, as in production. */
    @Test
    void theApplicationYamlDefaultsStart() {
        runner.withInitializer(MunicipalFeedTimingBoundsValidationTest::withTheApplicationYaml)
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * The defaults themselves keep a connect plus read timeout inside {@code max-response-time}, in Java
     * and in application.yml, and the two agree. The validation only runs on the values it is given, so
     * this pins the defaults even where nothing binds them.
     */
    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void everyFeedsDefaultsEndTheReadInsideMaxResponseTime(String source) {
        BeanWrapper java = new BeanWrapperImpl(new MunicipalSourceProperties());
        BeanWrapper yaml = new BeanWrapperImpl(
                MainApplicationYaml.bind("parkio.municipal", MunicipalSourceProperties.class, Map.of()));

        for (BeanWrapper defaults : new BeanWrapper[] {java, yaml}) {
            Duration connect = (Duration) defaults.getPropertyValue(source + ".connectTimeout");
            Duration read = (Duration) defaults.getPropertyValue(source + ".readTimeout");
            Duration max = (Duration) defaults.getPropertyValue(source + ".maxResponseTime");
            assertThat(connect.plus(read)).as("%s connect-timeout + read-timeout", source).isLessThan(max);
        }
        for (String setting : new String[] {"connectTimeout", "readTimeout", "maxResponseTime"}) {
            assertThat(java.getPropertyValue(source + "." + setting))
                    .as("Java default of %s.%s", source, setting)
                    .isEqualTo(yaml.getPropertyValue(source + "." + setting));
        }
    }

    static Stream<Arguments> settingsThatReachMaxResponseTime() {
        return Stream.of("izum", "ispark", "anpark", "konya", "kayseri").flatMap(source -> Stream.of(
                Arguments.of(source, "2s", "28s", "30s"),
                Arguments.of(source, "2s", "40s", "30s"),
                Arguments.of(source, "10s", "10s", "20s"),
                Arguments.of(source, "5s", "30s", "30s")));
    }

    @ParameterizedTest(name = "{0}: {1} + {2} against {3}")
    @MethodSource("settingsThatReachMaxResponseTime")
    void startupRefusesConnectPlusReadTimeoutThatReachesMaxResponseTime(
            String source, String connect, String read, String max) {
        runner.withPropertyValues(
                        "parkio.municipal." + source + ".connect-timeout=" + connect,
                        "parkio.municipal." + source + ".read-timeout=" + read,
                        "parkio.municipal." + source + ".max-response-time=" + max)
                .run(context -> {
                    assertThat(context).hasFailed();
                    FieldError error = IzumTimingBoundsValidationTest.fieldError(context.getStartupFailure());
                    assertThat(error.getField()).isEqualTo("readTimeout");
                    assertThat(error.getDefaultMessage())
                            .startsWith(envSegment(source) + " connect-timeout (" + connect + ") + read-timeout ("
                                    + read + ") must be less than max-response-time (" + max + ")")
                            .contains("Raise " + env(source, "MAX_RESPONSE_TIME") + " together with "
                                    + env(source, "READ_TIMEOUT"));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void aConnectPlusReadTimeoutJustInsideMaxResponseTimeStarts(String source) {
        runner.withPropertyValues(
                        "parkio.municipal." + source + ".connect-timeout=2s",
                        "parkio.municipal." + source + ".read-timeout=27999ms",
                        "parkio.municipal." + source + ".max-response-time=30s")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * {@code SimpleClientHttpRequestFactory} hands whole milliseconds to {@code HttpURLConnection}, which
     * treats 0 as no timeout, and it skips a negative value, which leaves none. A stalled feed would then
     * hold its sync: {@code max-response-time} is checked only between reads.
     */
    static Stream<Arguments> timeoutsTheHttpClientTreatsAsNone() {
        return Stream.of("izum", "ispark", "anpark", "konya", "kayseri").flatMap(source -> Stream.of(
                Arguments.of(source, "0s", "10s", "connectTimeout", "connect-timeout (0s)"),
                Arguments.of(source, "2s", "0s", "readTimeout", "read-timeout (0s)"),
                Arguments.of(source, "2s", "-1s", "readTimeout", "read-timeout (-1s)")));
    }

    @ParameterizedTest(name = "{0}: connect {1}, read {2}")
    @MethodSource("timeoutsTheHttpClientTreatsAsNone")
    void startupRefusesATimeoutThatTheHttpClientTreatsAsNone(
            String source, String connect, String read, String field, String named) {
        runner.withPropertyValues(
                        "parkio.municipal." + source + ".connect-timeout=" + connect,
                        "parkio.municipal." + source + ".read-timeout=" + read,
                        "parkio.municipal." + source + ".max-response-time=30s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    FieldError error = IzumTimingBoundsValidationTest.fieldError(context.getStartupFailure());
                    assertThat(error.getField()).isEqualTo(field);
                    assertThat(error.getDefaultMessage())
                            .startsWith(envSegment(source) + " " + named + " must be at least 1ms");
                });
    }

    /**
     * #661: {@code SimpleClientHttpRequestFactory} narrows {@code toMillis()} to an int, so a timeout of
     * 2^31 ms (about 24.9 days) or more wraps around, to a negative value that sets no timeout or to a much
     * shorter one. Startup refuses it. The 100-day max-response-time keeps the sum rule out of the way.
     */
    static Stream<Arguments> timeoutsTheHttpClientCannotHoldInAnInt() {
        return Stream.of("izum", "ispark", "anpark", "konya", "kayseri").flatMap(source -> Stream.of(
                Arguments.of(source, "2147483648ms", "2s", "connectTimeout", "connect-timeout (2147483648ms)"),
                Arguments.of(source, "2s", "2147483648ms", "readTimeout", "read-timeout (2147483648ms)"),
                Arguments.of(source, "2s", "25d", "readTimeout", "read-timeout (2160000s)")));
    }

    @ParameterizedTest(name = "{0}: connect {1}, read {2}")
    @MethodSource("timeoutsTheHttpClientCannotHoldInAnInt")
    void startupRefusesATimeoutTheHttpClientCannotHoldInAnInt(
            String source, String connect, String read, String field, String named) {
        runner.withPropertyValues(
                        "parkio.municipal." + source + ".connect-timeout=" + connect,
                        "parkio.municipal." + source + ".read-timeout=" + read,
                        "parkio.municipal." + source + ".max-response-time=100d")
                .run(context -> {
                    assertThat(context).hasFailed();
                    FieldError error = IzumTimingBoundsValidationTest.fieldError(context.getStartupFailure());
                    assertThat(error.getField()).isEqualTo(field);
                    assertThat(error.getDefaultMessage())
                            .startsWith(envSegment(source) + " " + named + " must be less than 2147483648ms");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void theLongestTimeoutsTheHttpClientCanHoldStart(String source) {
        runner.withPropertyValues(
                        "parkio.municipal." + source + ".connect-timeout=2147483647ms",
                        "parkio.municipal." + source + ".read-timeout=2147483647999999ns",
                        "parkio.municipal." + source + ".max-response-time=100d")
                .run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * A timeout far beyond the int limit is refused like any other, as a bind validation error. It does
     * not reach the connect + read sum, which would overflow a Duration and fail startup with an
     * ArithmeticException instead.
     */
    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void aTimeoutFarBeyondTheIntLimitIsAValidationError(String source) {
        runner.withPropertyValues(
                        "parkio.municipal." + source + ".connect-timeout=9223372036854775807s",
                        "parkio.municipal." + source + ".read-timeout=9223372036854775807s",
                        "parkio.municipal." + source + ".max-response-time=9223372036854775807s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    FieldError error = IzumTimingBoundsValidationTest.fieldError(context.getStartupFailure());
                    assertThat(error.getField()).isEqualTo("connectTimeout");
                    assertThat(error.getDefaultMessage()).contains("must be less than 2147483648ms");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"izum", "ispark", "anpark", "konya", "kayseri"})
    void theErrorNamesTheSourceTheValuesAndTheEnvironmentVariableThatSetThem(String source) {
        runner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(env(source, "CONNECT_TIMEOUT"), "2s", env(source, "READ_TIMEOUT"), "40s"))))
                .run(context -> {
                    FieldError error = IzumTimingBoundsValidationTest.fieldError(context.getStartupFailure());
                    assertThat(error.getDefaultMessage()).startsWith(envSegment(source)
                            + " connect-timeout (2s) + read-timeout (40s) must be less than max-response-time (30s)");
                    assertThat(String.valueOf(Origin.from(error))).contains(env(source, "READ_TIMEOUT"));
                });
    }

    /**
     * The former Konya and Kayseri default, 30s against a 5s connect timeout and a 30s
     * {@code max-response-time}, is refused. An env file that still sets it names the variable to fix.
     */
    @ParameterizedTest
    @ValueSource(strings = {"konya", "kayseri"})
    void theFormerKonyaAndKayseriReadTimeoutIsRefused(String source) {
        runner.withInitializer(MunicipalFeedTimingBoundsValidationTest::withTheApplicationYaml)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(env(source, "READ_TIMEOUT"), "30s"))))
                .run(context -> {
                    assertThat(context).hasFailed();
                    FieldError error = IzumTimingBoundsValidationTest.fieldError(context.getStartupFailure());
                    assertThat(error.getDefaultMessage()).startsWith(envSegment(source)
                            + " connect-timeout (5s) + read-timeout (30s) must be less than max-response-time (30s)");
                    assertThat(String.valueOf(Origin.from(error))).contains(env(source, "READ_TIMEOUT"));
                });
    }

    /** The source as its environment variables name it, such as {@code KONYA}. */
    private static String envSegment(String source) {
        return source.toUpperCase(Locale.ROOT);
    }

    private static String env(String source, String setting) {
        return "PARKIO_MUNICIPAL_" + envSegment(source) + "_" + setting;
    }

    /** Keeps the test JVM's environment and system properties out of the binding. */
    private static void withoutTheTestJvmEnvironment(ConfigurableApplicationContext context) {
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }

    private static void withTheApplicationYaml(ConfigurableApplicationContext context) {
        MainApplicationYaml.propertySources().forEach(context.getEnvironment().getPropertySources()::addLast);
    }
}
