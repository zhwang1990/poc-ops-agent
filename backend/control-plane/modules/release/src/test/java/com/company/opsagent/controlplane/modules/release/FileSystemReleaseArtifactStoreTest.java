package com.company.opsagent.controlplane.modules.release;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemReleaseArtifactStoreTest {

  @TempDir
  private Path tempDir;

  @Test
  void storesWarAndComputesSha256Checksum() {
    Path storage = tempDir.resolve("artifacts");
    ReleaseArtifactStore store = new FileSystemReleaseArtifactStore(storage, 1024);

    ReleaseArtifact artifact = store.storeWar(
        "orders",
        "dev",
        "orders-1.0.0.war",
        "alice",
        new ByteArrayInputStream("war".getBytes(UTF_8))).block();

    assertEquals(ArtifactType.WAR, artifact.artifactType());
    assertTrue(artifact.checksum().startsWith("sha256:"));
    assertEquals("orders-1.0.0.war", artifact.originalFilename());
    assertTrue(Files.exists(storage.resolve(artifact.artifactId() + ".war")));
    assertTrue(!Files.exists(storage.resolve("orders-1.0.0.war")));
  }

  @Test
  void rejectsNonWarAndOversizedArtifacts() {
    ReleaseArtifactStore store = new FileSystemReleaseArtifactStore(tempDir.resolve("artifacts"), 3);

    assertThrows(IllegalArgumentException.class, () -> store.storeWar(
        "orders",
        "dev",
        "orders.jar",
        "alice",
        new ByteArrayInputStream("jar".getBytes(UTF_8))).block());
    assertThrows(IllegalArgumentException.class, () -> store.storeWar(
        "orders",
        "dev",
        "orders.war",
        "alice",
        new ByteArrayInputStream("toolarge".getBytes(UTF_8))).block());
  }
}
