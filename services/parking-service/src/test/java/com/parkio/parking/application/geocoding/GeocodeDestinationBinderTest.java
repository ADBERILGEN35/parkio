package com.parkio.parking.application.geocoding;

import static org.assertj.core.api.Assertions.assertThat;

import com.parkio.parking.domain.place.Destination;
import com.parkio.parking.domain.place.DestinationSource;
import com.parkio.parking.domain.place.PlaceIdentity;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeocodeDestinationBinderTest {

    private final GeocodeDestinationBinder binder = new GeocodeDestinationBinder();

    @Test
    void bindsFullGeocodeResultWithProviderIdentity() {
        GeocodeResult result = new GeocodeResult(
                "314159",
                "Forum Bornova, Bornova, İzmir, Türkiye",
                "Forum Bornova",
                "Bornova, İzmir",
                38.4501,
                27.2112);

        Destination destination = binder.bind(result).orElseThrow();

        assertThat(destination.label()).isEqualTo("Forum Bornova");
        assertThat(destination.subtitle()).isEqualTo("Bornova, İzmir");
        assertThat(destination.latitude()).isEqualTo(38.4501);
        assertThat(destination.longitude()).isEqualTo(27.2112);
        assertThat(destination.source()).isEqualTo(DestinationSource.GEOCODING);
        assertThat(destination.placeIdentityOptional())
                .contains(PlaceIdentity.osmNominatim("314159"));
        assertThat(destination.placeIdentityOptional().orElseThrow().canonicalKey())
                .isEqualTo("osm-nominatim:314159");
    }

    @Test
    void bindsWithoutProviderIdentityWhenIdIsCoordinateFallback() {
        double lat = 38.45;
        double lng = 27.21;
        GeocodeResult result = new GeocodeResult(
                lat + "," + lng,
                "Somewhere",
                "Somewhere",
                "",
                lat,
                lng);

        Destination destination = binder.bind(result).orElseThrow();

        assertThat(destination.placeIdentityOptional()).isEmpty();
        assertThat(destination.label()).isEqualTo("Somewhere");
        assertThat(destination.subtitle()).isNull();
    }

    @Test
    void bindsWithoutIdentityWhenIdMatchesCoordinatePattern() {
        GeocodeResult result = new GeocodeResult(
                "41.01,28.97",
                "Pin",
                "Pin",
                "İstanbul",
                41.01,
                28.97);

        assertThat(binder.bind(result).orElseThrow().placeIdentityOptional()).isEmpty();
    }

    @Test
    void fallsBackToDisplayNameWhenPrimaryBlank() {
        GeocodeResult result = new GeocodeResult(
                "1",
                "Full Display Name",
                "  ",
                "İzmir",
                38.4,
                27.1);

        assertThat(binder.bind(result).orElseThrow().label()).isEqualTo("Full Display Name");
    }

    @Test
    void skipsInvalidCoordinatesWithoutThrowing() {
        GeocodeResult result = new GeocodeResult(
                "1",
                "Bad",
                "Bad",
                "",
                Double.NaN,
                27.0);

        assertThat(binder.bind(result)).isEmpty();
    }

    @Test
    void skipsBlankLabelsWithoutThrowing() {
        GeocodeResult result = new GeocodeResult(
                "1",
                "   ",
                "",
                "",
                38.0,
                27.0);

        assertThat(binder.bind(result)).isEmpty();
    }

    @Test
    void bindAllPreservesOrderAndSkipsInvalid() {
        List<GeocodeResult> results = List.of(
                new GeocodeResult("1", "A", "A", "", 38.0, 27.0),
                new GeocodeResult("2", "", "", "", Double.NaN, 27.0),
                new GeocodeResult("3", "C", "C", "Y", 39.0, 28.0));

        List<Destination> destinations = binder.bindAll(results);

        assertThat(destinations).hasSize(2);
        assertThat(destinations.get(0).label()).isEqualTo("A");
        assertThat(destinations.get(1).label()).isEqualTo("C");
    }

    @Test
    void bindNullReturnsEmpty() {
        assertThat(binder.bind(null)).isEmpty();
        assertThat(binder.bindAll(null)).isEmpty();
    }

    @Test
    void mappingIsDeterministic() {
        GeocodeResult result = new GeocodeResult(
                "55",
                "Forum Bornova, Bornova",
                "Forum Bornova",
                "Bornova",
                38.45,
                27.21);

        Destination first = binder.bind(result).orElseThrow();
        Destination second = binder.bind(result).orElseThrow();

        assertThat(first).isEqualTo(second);
    }

    @Test
    void doesNotLeakRawProviderPayloadFields() {
        Destination destination = binder.bind(new GeocodeResult(
                "9",
                "X",
                "X",
                "Y",
                38.0,
                27.0)).orElseThrow();

        assertThat(destination.toString()).doesNotContain("place_id");
        assertThat(destination.toString()).doesNotContain("display_name");
        assertThat(Destination.class.getRecordComponents())
                .extracting(rc -> rc.getName())
                .doesNotContain("raw", "payload", "providerPayload");
    }

    @Test
    void coordinateFallbackDetectionHelpers() {
        assertThat(GeocodeDestinationBinder.isCoordinateFallbackId("38.45,27.21", 38.45, 27.21)).isTrue();
        assertThat(GeocodeDestinationBinder.isCoordinateFallbackId("314159", 38.45, 27.21)).isFalse();
        assertThat(Optional.ofNullable(null)).isEmpty();
    }
}
