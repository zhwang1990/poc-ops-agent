package com.company.opsagent.controlplane.modules.release;

import java.time.Clock;
import java.time.OffsetDateTime;
import reactor.core.publisher.Mono;

public class ReleaseCredentialService {

  private final ReleaseCatalogStore store;
  private final ReleaseCredentialSecretCodec codec;
  private final Clock clock;

  public ReleaseCredentialService(
      ReleaseCatalogStore store,
      ReleaseCredentialSecretCodec codec,
      Clock clock) {
    this.store = ReleaseValues.required(store, "store");
    this.codec = ReleaseValues.required(codec, "codec");
    this.clock = ReleaseValues.required(clock, "clock");
  }

  public Mono<ReleaseCredentialSummary> createOrRotate(
      String credentialAlias,
      ServerType serverType,
      String plaintext,
      String operatorId) {
    String alias = ReleaseValues.requiredText(credentialAlias, "credentialAlias");
    ServerType type = ReleaseValues.required(serverType, "serverType");
    ReleaseValues.requiredText(operatorId, "operatorId");
    ReleaseCredentialSecretCodec.EncryptedCredential encrypted = codec.encrypt(plaintext);
    OffsetDateTime updatedAt = OffsetDateTime.now(clock);

    return store.findCredential(alias)
        .map(ReleaseCredential::createdAt)
        .defaultIfEmpty(updatedAt)
        .flatMap(createdAt -> store.saveCredential(new ReleaseCredential(
            alias,
            type,
            encrypted.ciphertext(),
            encrypted.nonce(),
            encrypted.algorithm(),
            encrypted.fingerprint(),
            createdAt,
            updatedAt)))
        .map(ReleaseCredential::summary);
  }
}
