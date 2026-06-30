package com.company.opsagent.controlplane.modules.agentruntime;

import java.net.URI;

/**
 * Resolves OpenAI-compatible API endpoint paths from administrator supplied base URLs.
 */
public final class OpenAiCompatibleEndpoint {

  private OpenAiCompatibleEndpoint() {
  }

  public static URI chatCompletionsUri(String baseUrl) {
    return URI.create(chatCompletionsUrl(baseUrl));
  }

  public static String chatCompletionsUrl(String baseUrl) {
    return apiBaseUrl(baseUrl) + "/chat/completions";
  }

  public static String apiBaseUrl(String baseUrl) {
    String trimmed = trimTrailingSlash(baseUrl.strip());
    URI uri = URI.create(trimmed);
    String path = uri.getPath();
    return path != null && path.endsWith("/v1")
        ? trimmed
        : trimmed + "/v1";
  }

  private static String trimTrailingSlash(String value) {
    String trimmed = value;
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }
}
