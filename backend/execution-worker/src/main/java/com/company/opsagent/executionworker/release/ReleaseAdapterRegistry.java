package com.company.opsagent.executionworker.release;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ReleaseAdapterRegistry {

  private final Map<String, ReleaseAdapter> adaptersByMode;

  public ReleaseAdapterRegistry(List<ReleaseAdapter> adapters) {
    adaptersByMode = List.copyOf(adapters).stream()
        .collect(Collectors.toUnmodifiableMap(ReleaseAdapter::managementMode, Function.identity()));
  }

  public Optional<ReleaseAdapter> find(String managementMode) {
    return Optional.ofNullable(adaptersByMode.get(managementMode));
  }
}
