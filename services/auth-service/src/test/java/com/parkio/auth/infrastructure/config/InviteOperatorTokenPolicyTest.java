package com.parkio.auth.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class InviteOperatorTokenPolicyTest {

    /** The example env files that ship an operator token (U17 style: read them, never restate them). */
    static final List<Path> EXAMPLE_ENV_FILES = List.of(
            Path.of("../../docker/.env.invite-production.example"),
            Path.of("../../docker/.env.azure-hosted-beta.example"));

    /** The PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN value an example env file ships. */
    static String exampleToken(Path envFile) throws IOException {
        assertThat(envFile).as("example env file").exists();
        return Files.readAllLines(envFile, StandardCharsets.UTF_8).stream()
                .filter(line -> line.startsWith("PARKIO_REGISTRATION_INVITE_OPERATOR_TOKEN="))
                .map(line -> line.substring(line.indexOf('=') + 1).strip().replaceAll("^\"|\"$", ""))
                .findFirst()
                .orElseThrow(() -> new AssertionError(envFile + " ships no operator token"));
    }

    @Test
    void theExampleEnvTokensArePlaceholders() throws IOException {
        for (Path envFile : EXAMPLE_ENV_FILES) {
            assertThat(InviteOperatorTokenPolicy.refusal(exampleToken(envFile))).as(envFile.toString())
                    .contains("PLACEHOLDER");
        }
    }

    @Test
    void anyReplaceMeValueIsAPlaceholderWhateverItsCaseOrLength() {
        assertThat(InviteOperatorTokenPolicy.refusal("REPLACE_ME_a_long_enough_value_for_the_length_rule"))
                .contains("PLACEHOLDER");
        assertThat(InviteOperatorTokenPolicy.refusal("replace_me_x")).contains("PLACEHOLDER");
        assertThat(InviteOperatorTokenPolicy.refusal("  Replace_Me_Invite_Operator_Token_Min_32_Chars  "))
                .contains("PLACEHOLDER");
    }

    @Test
    void anEmptyTokenIsRefused() {
        assertThat(InviteOperatorTokenPolicy.refusal(null)).contains("EMPTY");
        assertThat(InviteOperatorTokenPolicy.refusal("")).contains("EMPTY");
        assertThat(InviteOperatorTokenPolicy.refusal("   ")).contains("EMPTY");
    }

    @Test
    void aTokenShorterThanTheMinimumIsRefused() {
        assertThat(InviteOperatorTokenPolicy.refusal("x".repeat(InviteOperatorTokenPolicy.MIN_LENGTH - 1)))
                .contains("TOO_SHORT");
        assertThat(InviteOperatorTokenPolicy.refusal("operator-token")).contains("TOO_SHORT");
    }

    @Test
    void aRealTokenOfTheMinimumLengthIsAccepted() {
        assertThat(InviteOperatorTokenPolicy.refusal("k".repeat(InviteOperatorTokenPolicy.MIN_LENGTH))).isEmpty();
        assertThat(InviteOperatorTokenPolicy.refusal("SYNTH_PLACEHOLDER_value_0123456789abcdef")).isEmpty();
    }
}
