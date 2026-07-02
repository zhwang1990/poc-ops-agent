package com.company.opsagent.controlplane.modules.release;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public record ReleaseScriptProfile(String profileId, List<ReleaseScriptParameter> parameters) {

  private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final int MAX_PARAMETERS = 40;

  public ReleaseScriptProfile {
    profileId = ReleaseValues.requiredText(profileId, "scriptProfile.profileId");
    if (!PROFILE_ID_PATTERN.matcher(profileId).matches()) {
      throw new IllegalArgumentException("scriptProfile.profileId is invalid");
    }
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
    if (parameters.size() > MAX_PARAMETERS) {
      throw new IllegalArgumentException("scriptProfile.parameters is too large");
    }
    Set<String> names = new HashSet<>();
    List<ReleaseScriptParameter> normalized = new ArrayList<>();
    for (ReleaseScriptParameter parameter : parameters) {
      ReleaseScriptParameter required = ReleaseValues.required(parameter, "scriptProfile.parameter");
      if (!names.add(required.name())) {
        throw new IllegalArgumentException("scriptProfile parameter names must be unique");
      }
      normalized.add(required);
    }
    parameters = List.copyOf(normalized);
  }
}
