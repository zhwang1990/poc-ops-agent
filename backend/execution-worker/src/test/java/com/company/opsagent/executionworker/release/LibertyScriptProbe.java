package com.company.opsagent.executionworker.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class LibertyScriptProbe {

  private LibertyScriptProbe() {
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.exit(2);
      return;
    }
    Path output = Path.of(args[0]);
    Files.writeString(output, String.join("|", args), StandardCharsets.UTF_8);
  }
}
