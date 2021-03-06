/*
 * Copyright 2021 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package tech.pegasys.teku.validator.client.loader;

import static java.util.stream.Collectors.toList;
import static tech.pegasys.teku.infrastructure.logging.StatusLogger.STATUS_LOG;

import java.net.http.HttpClient;
import java.util.List;
import java.util.function.Supplier;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.core.signatures.Signer;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.ThrottlingTaskQueue;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.validator.api.ValidatorConfig;
import tech.pegasys.teku.validator.client.signer.ExternalSigner;
import tech.pegasys.teku.validator.client.signer.ExternalSignerStatusLogger;
import tech.pegasys.teku.validator.client.signer.ExternalSignerUpcheck;

public class ExternalValidatorSource implements ValidatorSource {

  private final ValidatorConfig config;
  private final Supplier<HttpClient> externalSignerHttpClientFactory;
  private final PublicKeyLoader publicKeyLoader;
  private final ThrottlingTaskQueue externalSignerTaskQueue;

  private ExternalValidatorSource(
      final ValidatorConfig config,
      final Supplier<HttpClient> externalSignerHttpClientFactory,
      final PublicKeyLoader publicKeyLoader,
      final ThrottlingTaskQueue externalSignerTaskQueue) {
    this.config = config;
    this.externalSignerHttpClientFactory = externalSignerHttpClientFactory;
    this.publicKeyLoader = publicKeyLoader;
    this.externalSignerTaskQueue = externalSignerTaskQueue;
  }

  public static ExternalValidatorSource create(
      final MetricsSystem metricsSystem,
      final ValidatorConfig config,
      final Supplier<HttpClient> externalSignerHttpClientFactory,
      final PublicKeyLoader publicKeyLoader,
      final AsyncRunner asyncRunner) {
    final ThrottlingTaskQueue externalSignerTaskQueue =
        new ThrottlingTaskQueue(
            config.getValidatorExternalSignerConcurrentRequestLimit(),
            metricsSystem,
            TekuMetricCategory.VALIDATOR,
            "external_signer_request_queue_size");
    setupExternalSignerStatusLogging(config, externalSignerHttpClientFactory, asyncRunner);
    return new ExternalValidatorSource(
        config, externalSignerHttpClientFactory, publicKeyLoader, externalSignerTaskQueue);
  }

  @Override
  public List<ValidatorProvider> getAvailableValidators() {
    final List<BLSPublicKey> publicKeys =
        publicKeyLoader.getPublicKeys(config.getValidatorExternalSignerPublicKeySources());
    return publicKeys.stream().map(ExternalValidatorProvider::new).collect(toList());
  }

  private static void setupExternalSignerStatusLogging(
      final ValidatorConfig config,
      final Supplier<HttpClient> externalSignerHttpClientFactory,
      final AsyncRunner asyncRunner) {
    final ExternalSignerUpcheck externalSignerUpcheck =
        new ExternalSignerUpcheck(
            externalSignerHttpClientFactory.get(),
            config.getValidatorExternalSignerUrl(),
            config.getValidatorExternalSignerTimeout());
    final ExternalSignerStatusLogger externalSignerStatusLogger =
        new ExternalSignerStatusLogger(
            STATUS_LOG,
            externalSignerUpcheck::upcheck,
            config.getValidatorExternalSignerUrl(),
            asyncRunner);
    // initial status log
    externalSignerStatusLogger.log();
    // recurring status log
    externalSignerStatusLogger.logWithFixedDelay();
  }

  private class ExternalValidatorProvider implements ValidatorProvider {
    private final BLSPublicKey publicKey;

    private ExternalValidatorProvider(final BLSPublicKey publicKey) {
      this.publicKey = publicKey;
    }

    @Override
    public BLSPublicKey getPublicKey() {
      return publicKey;
    }

    @Override
    public Signer createSigner() {
      return new ExternalSigner(
          externalSignerHttpClientFactory.get(),
          config.getValidatorExternalSignerUrl(),
          publicKey,
          config.getValidatorExternalSignerTimeout(),
          externalSignerTaskQueue);
    }
  }
}
