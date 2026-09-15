package com.parkio.parking.externalsource;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.externalsource.izelman.IzelmanSourceKeys;
import com.parkio.parking.externalsource.izelman.SourceAgeClassification;
import com.parkio.parking.externalsource.izelman.TariffCurrentness;
import com.parkio.parking.infrastructure.config.IzelmanProperties;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MunicipalSourcePublicationPolicyTest {
    private MunicipalSourceProperties municipal;
    private IzelmanProperties izelman;
    private MunicipalSourcePublicationPolicy policy;

    @BeforeEach
    void setUp() {
        municipal = new MunicipalSourceProperties();
        municipal.getOsm().setPublicationEnabled(true);
        izelman = new IzelmanProperties();
        izelman.setFacilityPublicationEnabled(false);
        izelman.setRoadsidePublicationEnabled(false);
        izelman.setTariffPublicationEnabled(false);
        policy = new MunicipalSourcePublicationPolicy(municipal, izelman);
    }

    @Test
    void izumRemainsPublishableWhenAttributionWouldMentionIzelman() {
        assertThat(policy.isSourceLinkPublishable(MunicipalSourceIdentity.IZUM)).isTrue();
        assertThat(policy.isFacilityPublishable(Set.of(MunicipalSourceIdentity.IZUM), MunicipalSourceIdentity.IZUM))
                .isTrue();
        assertThat(MunicipalSourceIdentity.looksLikeDisclaimerText(
                "Parkio is not affiliated with IZELMAN A.S.")).isTrue();
    }

    @Test
    void publisherRenameDoesNotChangeIzumIdentity() {
        assertThat(MunicipalSourceIdentity.familyOf(MunicipalSourceIdentity.IZUM))
                .isEqualTo(MunicipalSourceIdentity.FAMILY_IZUM);
        assertThat(MunicipalSourceIdentity.isIzelman("Izmir Buyuksehir Belediyesi / IZELMAN A.S.")).isFalse();
    }

    @Test
    void izelmanOnlyHiddenWhenFacilityPublicationDisabled() {
        assertThat(policy.isFacilityPublishable(
                Set.of(IzelmanSourceKeys.OPEN), IzelmanSourceKeys.OPEN)).isFalse();
        izelman.setFacilityPublicationEnabled(true);
        assertThat(policy.isFacilityPublishable(
                Set.of(IzelmanSourceKeys.OPEN), IzelmanSourceKeys.OPEN)).isTrue();
    }

    @Test
    void osmUnaffectedByIzelmanPublicationFlag() {
        izelman.setFacilityPublicationEnabled(false);
        municipal.getOsm().setPublicationEnabled(true);
        assertThat(policy.isFacilityPublishable(
                Set.of(MunicipalSourceIdentity.OSM), MunicipalSourceIdentity.OSM)).isTrue();
        municipal.getOsm().setPublicationEnabled(false);
        assertThat(policy.isFacilityPublishable(
                Set.of(MunicipalSourceIdentity.OSM), MunicipalSourceIdentity.OSM)).isFalse();
    }

    @Test
    void multiSourceIzumPlusIzelmanRemainsVisibleWhenIzelmanGated() {
        assertThat(policy.isFacilityPublishable(
                Set.of(MunicipalSourceIdentity.IZUM, IzelmanSourceKeys.OPEN),
                IzelmanSourceKeys.OPEN)).isTrue();
        assertThat(policy.mayContributeLiveOccupancy(
                Set.of(MunicipalSourceIdentity.IZUM, IzelmanSourceKeys.OPEN))).isTrue();
        assertThat(policy.mayContributeIzelmanInventoryFields(
                Set.of(MunicipalSourceIdentity.IZUM, IzelmanSourceKeys.OPEN))).isFalse();
    }

    @Test
    void multiSourceOsmPlusIzelmanRemainsVisibleThroughOsm() {
        assertThat(policy.isFacilityPublishable(
                Set.of(MunicipalSourceIdentity.OSM, IzelmanSourceKeys.BARRIER),
                IzelmanSourceKeys.BARRIER)).isTrue();
        assertThat(policy.mayContributeIzelmanInventoryFields(
                Set.of(MunicipalSourceIdentity.OSM, IzelmanSourceKeys.BARRIER))).isFalse();
        assertThat(policy.mayContributeOsmFields(
                Set.of(MunicipalSourceIdentity.OSM, IzelmanSourceKeys.BARRIER))).isTrue();
    }

    @Test
    void roadsideAndTariffUseIndependentFlags() {
        assertThat(policy.isRoadsidePublishable(IzelmanSourceKeys.ROADSIDE)).isFalse();
        assertThat(policy.isTariffPublishable(IzelmanSourceKeys.TARIFFS)).isFalse();
        izelman.setRoadsidePublicationEnabled(true);
        izelman.setTariffPublicationEnabled(true);
        assertThat(policy.isRoadsidePublishable(IzelmanSourceKeys.ROADSIDE)).isTrue();
        assertThat(policy.isTariffPublishable(IzelmanSourceKeys.TARIFFS)).isTrue();
    }

    @Test
    void enablingTariffPublicationDoesNotUpgradeHistoricalOrUnknownToCurrent() {
        izelman.setTariffPublicationEnabled(true);
        assertThat(policy.publicTariffCurrentness(
                TariffCurrentness.HISTORICAL, SourceAgeClassification.HISTORICAL))
                .isEqualTo(TariffCurrentness.HISTORICAL);
        assertThat(policy.publicTariffCurrentness(
                TariffCurrentness.UNKNOWN, SourceAgeClassification.AGING))
                .isEqualTo(TariffCurrentness.UNKNOWN);
        assertThat(policy.publicTariffCurrentness(
                TariffCurrentness.CURRENT, SourceAgeClassification.HISTORICAL))
                .isEqualTo(TariffCurrentness.HISTORICAL);
    }

    @Test
    void konyaAndKayseriRemainPublishableAsInventorySources() {
        assertThat(policy.isSourceLinkPublishable(MunicipalSourceIdentity.KONYA)).isTrue();
        assertThat(policy.isSourceLinkPublishable(MunicipalSourceIdentity.KAYSERI)).isTrue();
        assertThat(policy.isFacilityPublishable(
                Set.of(MunicipalSourceIdentity.KAYSERI), MunicipalSourceIdentity.KAYSERI)).isTrue();
        assertThat(policy.mayContributeLiveOccupancy(Set.of(MunicipalSourceIdentity.KAYSERI))).isFalse();
    }
}