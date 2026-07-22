package com.company.opsagent.executionworker.release;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

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
    if (args.length > 1 && "--stdout".equals(args[1])) {
      System.out.println("deploy started");
      System.out.println("token=" + UUID.randomUUID());
      System.out.println("deploy completed");
    }
  }
}
