package com.company.opsagent.controlplane.modules.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class FileSystemReleaseArtifactStore implements ReleaseArtifactStore {

  private final Path storagePath;
  private final long maxArtifactBytes;

  public FileSystemReleaseArtifactStore(Path storagePath, long maxArtifactBytes) {
    this.storagePath = ReleaseValues.required(storagePath, "storagePath")
        .toAbsolutePath()
        .normalize();
    if (maxArtifactBytes < 1) {
      throw new IllegalArgumentException("maxArtifactBytes must be positive");
    }
    this.maxArtifactBytes = maxArtifactBytes;
  }

  @Override
  public Mono<ReleaseArtifact> storeWar(
      String applicationId,
      String targetEnvironment,
      String originalFilename,
      String uploadedBy,
      InputStream inputStream) {
    return Mono.fromCallable(() -> storeWarBlocking(
            applicationId,
            targetEnvironment,
            originalFilename,
            uploadedBy,
            inputStream))
        .subscribeOn(Schedulers.boundedElastic());
  }

  private ReleaseArtifact storeWarBlocking(
      String applicationId,
      String targetEnvironment,
      String originalFilename,
      String uploadedBy,
      InputStream inputStream) throws IOException {
    String appId = ReleaseValues.requiredText(applicationId, "applicationId");
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    String fileName = safeWarFilename(originalFilename);
    String operator = ReleaseValues.requiredText(uploadedBy, "uploadedBy");
    ReleaseValues.required(inputStream, "inputStream");

    Files.createDirectories(storagePath);
    String artifactId = "art-" + UUID.randomUUID();
    Path target = storagePath.resolve(artifactId + ".war").normalize();
    if (!target.startsWith(storagePath)) {
      throw new IllegalStateException("artifact target path escaped storage directory");
    }

    MessageDigest digest = sha256();
    long byteSize = 0;
    try (InputStream input = inputStream;
        OutputStream output = Files.newOutputStream(target)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        byteSize += read;
        if (byteSize > maxArtifactBytes) {
          output.close();
          Files.deleteIfExists(target);
          throw new IllegalArgumentException("artifact exceeds maxArtifactBytes");
        }
        digest.update(buffer, 0, read);
        output.write(buffer, 0, read);
      }
    }

    return ReleaseArtifact.create(
        artifactId,
        appId,
        environment.value(),
        ArtifactType.WAR,
        "sha256:" + HexFormat.of().formatHex(digest.digest()),
        fileName,
        artifactId + ".war",
        byteSize,
        operator,
        "TOMCAT_UPLOAD",
        true);
  }

  private String safeWarFilename(String originalFilename) {
    String fileName = Path.of(ReleaseValues.requiredText(originalFilename, "originalFilename"))
        .getFileName()
        .toString();
    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".war")) {
      throw new IllegalArgumentException("only WAR artifacts are supported");
    }
    return fileName;
  }

  private MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
