package com.parkio.parking.domain.place;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DestinationTest {

    @Test
    void createsValidDestination() {
        Destination destination = Destination.of(
                "Forum Bornova",
                38.4501,
                27.2112,
                DestinationSource.GEOCODING);

        assertThat(destination.label()).isEqualTo("Forum Bornova");
        assertThat(destination.latitude()).isEqualTo(38.4501);
        assertThat(destination.longitude()).isEqualTo(27.2112);
        assertThat(destination.source()).isEqualTo(DestinationSource.GEOCODING);
        assertThat(destination.placeIdentityOptional()).isEmpty();
        assertThat(destination.subtitleOptional()).isEmpty();
    }

    @Test
    void trimsAndCollapsesWhitespaceInLabel() {
        Destination destination = Destination.of(
                "  Forum   Bornova  ",
                38.45,
                27.21,
                DestinationSource.MAP_PIN);

        assertThat(destination.label()).isEqualTo("Forum Bornova");
    }

    @Test
    void preservesTurkishCharacters() {
        Destination destination = Destination.of(
                "Konak İskele Çarşı",
                38.4192,
                27.1287,
                DestinationSource.GEOCODING,
                null,
                "İzmir, Türkiye");

        assertThat(destination.label()).isEqualTo("Konak İskele Çarşı");
        assertThat(destination.subtitle()).isEqualTo("İzmir, Türkiye");
    }

    @Test
    void acceptsOptionalPlaceIdentityAndSubtitle() {
        PlaceIdentity identity = PlaceIdentity.osmNominatim("12345");
        Destination destination = Destination.of(
                "Forum Bornova",
                38.45,
                27.21,
                DestinationSource.GEOCODING,
                identity,
                "Bornova, İzmir");

        assertThat(destination.placeIdentityOptional()).contains(identity);
        assertThat(destination.subtitleOptional()).contains("Bornova, İzmir");
    }

    @Test
    void mapPinFactoryUsesMapPinSource() {
        Destination destination = Destination.mapPin("Drop pin", 41.0, 29.0);
        assertThat(destination.source()).isEqualTo(DestinationSource.MAP_PIN);
    }

    @Test
    void blankLabelRejected() {
        assertThatThrownBy(() -> Destination.of("   ", 38.0, 27.0, DestinationSource.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");
    }

    @Test
    void nullLabelRejected() {
        assertThatThrownBy(() -> Destination.of(null, 38.0, 27.0, DestinationSource.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("label");
    }

    @Test
    void nullSourceRejected() {
        assertThatThrownBy(() -> Destination.of("X", 38.0, 27.0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source");
    }

    @Test
    void labelMaxLengthEnforced() {
        String tooLong = "a".repeat(Destination.MAX_LABEL_LENGTH + 1);
        assertThatThrownBy(() -> Destination.of(tooLong, 38.0, 27.0, DestinationSource.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max length");
    }

    @Test
    void blankSubtitleBecomesNull() {
        Destination destination = Destination.of(
                "Forum",
                38.0,
                27.0,
                DestinationSource.SYSTEM,
                null,
                "   ");
        assertThat(destination.subtitle()).isNull();
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.0, 0.0, 90.0, 38.450123456})
    void acceptsBoundaryAndPreciseLatitudes(double latitude) {
        Destination destination = Destination.of("X", latitude, 27.0, DestinationSource.SYSTEM);
        assertThat(destination.latitude()).isEqualTo(latitude);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.0, 0.0, 180.0, 27.211234567})
    void acceptsBoundaryAndPreciseLongitudes(double longitude) {
        Destination destination = Destination.of("X", 38.0, longitude, DestinationSource.SYSTEM);
        assertThat(destination.longitude()).isEqualTo(longitude);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-90.0001, 90.0001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLatitudes(double latitude) {
        assertThatThrownBy(() -> Destination.of("X", latitude, 27.0, DestinationSource.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("latitude");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-180.0001, 180.0001, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void rejectsInvalidLongitudes(double longitude) {
        assertThatThrownBy(() -> Destination.of("X", 38.0, longitude, DestinationSource.SYSTEM))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("longitude");
    }

    @Test
    void valueEquality() {
        Destination a = Destination.of("Forum", 38.45, 27.21, DestinationSource.GEOCODING);
        Destination b = Destination.of("Forum", 38.45, 27.21, DestinationSource.GEOCODING);
        Destination c = Destination.of("Other", 38.45, 27.21, DestinationSource.GEOCODING);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void doesNotCarryParkingOrPreferenceFields() {
        // Structural guarantee: Destination record components are place intent only.
        assertThat(Destination.class.getRecordComponents())
                .extracting(rc -> rc.getName())
                .containsExactly("label", "latitude", "longitude", "source", "placeIdentity", "subtitle");
    }
}
