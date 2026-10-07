package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

/**
 * The recovery target rule (PR #295 review B3) on the shared identity cases, which the Python
 * restore applies through {@code check_target_identity}: a target on the production cluster is
 * refused whatever its database name, and an identity that does not parse strictly is ambiguous.
 */
class DatabaseIdentityTest {

    @Test
    void theSharedCasesGiveThePythonDecisions() {
        JsonNode cases = DurableEvidenceFixtures.json("database-identities.json");
        String production = cases.path("productionIdentity").asText();
        assertThat(cases.path("cases").size()).isGreaterThanOrEqualTo(14);
        for (JsonNode item : cases.path("cases")) {
            String target = item.path("target").asText();
            if (item.path("refusal").isNull()) {
                DatabaseIdentity.checkTarget(target, production);
            } else {
                assertThatThrownBy(() -> DatabaseIdentity.checkTarget(target, production))
                        .as(item.path("name").asText())
                        .isInstanceOf(DurableEvidenceException.class)
                        .hasMessage(item.path("refusal").asText());
            }
        }
    }

    @Test
    void anIdentityParsesIntoItsClusterAndDatabase() {
        assertThat(DatabaseIdentity.parse("postgresql:7000000000000000099:parkio_auth"))
                .isEqualTo(new DatabaseIdentity("7000000000000000099", "parkio_auth"));
        assertThatThrownBy(() -> DatabaseIdentity.parse(null))
                .isInstanceOf(DurableEvidenceException.class).hasMessage("ambiguous database identity");
    }

    @Test
    void anAmbiguousProductionIdentityRefusesEveryTarget() {
        assertThatThrownBy(() -> DatabaseIdentity.checkTarget("postgresql:7000000000000000099:parkio_auth",
                "postgresql:7000000000000000001"))
                .isInstanceOf(DurableEvidenceException.class).hasMessage("ambiguous database identity");
    }
}
