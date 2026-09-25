package com.parkio.parking.externalsource.provider;

import com.parkio.parking.externalsource.MunicipalParkingSourceAdapter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Spring-friendly registry of installed live parking-source adapters.
 * Duplicate source keys fail closed at construction. File importers (OSM / İZELMAN)
 * remain on their dedicated orchestration paths and are not registered here.
 */
public final class ParkingProviderRegistry {
    private final Map<String, MunicipalParkingSourceAdapter> adaptersBySourceKey;
    private final Map<String, ParkingDataSourceDescriptor> descriptorsBySourceKey;

    public ParkingProviderRegistry(List<MunicipalParkingSourceAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        Map<String, MunicipalParkingSourceAdapter> bySource = new LinkedHashMap<>();
        Map<String, ParkingDataSourceDescriptor> byDescriptor = new LinkedHashMap<>();
        for (MunicipalParkingSourceAdapter adapter : adapters) {
            String sourceKey = adapter.sourceKey();
            if (bySource.containsKey(sourceKey)) {
                throw new IllegalStateException("Duplicate municipal parking adapter for source: " + sourceKey);
            }
            ParkingDataSourceDescriptor descriptor = ParkingProviderCatalog.find(sourceKey)
                    .orElseGet(() -> new ParkingDataSourceDescriptor(
                            adapter.providerId(),
                            sourceKey,
                            adapter.providerId().name().toLowerCase(),
                            adapter.capabilities(),
                            adapter.reconciliationMode(),
                            sourceKey,
                            sourceKey,
                            false));
            bySource.put(sourceKey, adapter);
            byDescriptor.put(sourceKey, descriptor);
        }
        this.adaptersBySourceKey = Map.copyOf(bySource);
        this.descriptorsBySourceKey = Map.copyOf(byDescriptor);
    }

    public Optional<MunicipalParkingSourceAdapter> adapter(String sourceKey) {
        return Optional.ofNullable(adaptersBySourceKey.get(sourceKey));
    }

    public MunicipalParkingSourceAdapter requireAdapter(String sourceKey) {
        return adapter(sourceKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown municipal source: " + sourceKey));
    }

    public Optional<ParkingDataSourceDescriptor> descriptor(String sourceKey) {
        return Optional.ofNullable(descriptorsBySourceKey.get(sourceKey))
                .or(() -> ParkingProviderCatalog.find(sourceKey));
    }

    public Set<String> registeredSourceKeys() {
        return adaptersBySourceKey.keySet();
    }

    public Map<String, MunicipalParkingSourceAdapter> adapters() {
        return adaptersBySourceKey;
    }

    public List<MunicipalParkingSourceAdapter> adapterList() {
        return List.copyOf(adaptersBySourceKey.values());
    }

    public static Map<String, MunicipalParkingSourceAdapter> index(List<MunicipalParkingSourceAdapter> adapters) {
        return adapters.stream().collect(Collectors.toUnmodifiableMap(
                MunicipalParkingSourceAdapter::sourceKey, Function.identity(),
                (a, b) -> {
                    throw new IllegalStateException("Duplicate municipal parking adapter for source: "
                            + a.sourceKey());
                }));
    }
}
