package com.parkio.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedNotificationCatalogTest {

    @Test
    void pointEarnedRendersTrAndEnFromSameVariables() {
        Map<String, String> variables = Map.of(
                "points", "15",
                "totalPoints", "120");

        NotificationTemplate.RenderedContent tr =
                LocalizedNotificationCatalog.render("pointEarned", NotificationLocale.TR, variables);
        NotificationTemplate.RenderedContent en =
                LocalizedNotificationCatalog.render("pointEarned", NotificationLocale.EN, variables);

        assertThat(tr.title()).isEqualTo("Puan kazandınız");
        assertThat(tr.body()).isEqualTo("15 puan kazandınız. Toplam: 120.");

        assertThat(en.title()).isEqualTo("You earned points");
        assertThat(en.body()).isEqualTo("You earned 15 points. Total: 120.");
    }

    @Test
    void trustChangedRendersTrAndEnWithLocalizedDirectionLabels() {
        Map<String, String> variables = Map.of(
                "previousScore", "100",
                "newScore", "85",
                "direction", "decreased");

        NotificationTemplate.RenderedContent tr =
                LocalizedNotificationCatalog.render("trustChanged", NotificationLocale.TR, variables);
        NotificationTemplate.RenderedContent en =
                LocalizedNotificationCatalog.render("trustChanged", NotificationLocale.EN, variables);

        assertThat(tr.title()).isEqualTo("Dikkat");
        assertThat(tr.body()).isEqualTo("Güven puanınız 100 değerinden 85 değerine düştü.");

        assertThat(en.title()).isEqualTo("Heads up");
        assertThat(en.body()).isEqualTo("Your trust score decreased from 100 to 85.");
    }

    @Test
    void levelUpRendersTrAndEn() {
        Map<String, String> variables = Map.of("level", "4");

        NotificationTemplate.RenderedContent tr =
                LocalizedNotificationCatalog.render("levelUp", NotificationLocale.TR, variables);
        NotificationTemplate.RenderedContent en =
                LocalizedNotificationCatalog.render("levelUp", NotificationLocale.EN, variables);

        assertThat(tr.body()).isEqualTo("Tebrikler — 4. seviyeye ulaştınız.");
        assertThat(en.body()).isEqualTo("Congratulations — you reached level 4.");
    }
}
