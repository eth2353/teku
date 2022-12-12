/*
 * Copyright ConsenSys Software Inc., 2022
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

package tech.pegasys.teku.validator.client;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import tech.pegasys.teku.infrastructure.async.AsyncRunner;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.events.EventChannels;
import tech.pegasys.teku.infrastructure.io.SyncDataAccessor;
import tech.pegasys.teku.infrastructure.io.SystemSignalListener;
import tech.pegasys.teku.infrastructure.logging.ValidatorLogger;
import tech.pegasys.teku.infrastructure.metrics.TekuMetricCategory;
import tech.pegasys.teku.infrastructure.restapi.RestApi;
import tech.pegasys.teku.infrastructure.time.TimeProvider;
import tech.pegasys.teku.provider.JsonProvider;
import tech.pegasys.teku.service.serviceutils.Service;
import tech.pegasys.teku.service.serviceutils.ServiceConfig;
import tech.pegasys.teku.service.serviceutils.layout.DataDirLayout;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.signatures.LocalSlashingProtector;
import tech.pegasys.teku.spec.signatures.SlashingProtector;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;
import tech.pegasys.teku.validator.api.ValidatorConfig;
import tech.pegasys.teku.validator.api.ValidatorTimingChannel;
import tech.pegasys.teku.validator.beaconnode.BeaconNodeApi;
import tech.pegasys.teku.validator.beaconnode.GenesisDataProvider;
import tech.pegasys.teku.validator.client.doppelganger.DoppelgangerDetectionAction;
import tech.pegasys.teku.validator.client.doppelganger.DoppelgangerDetectionAlert;
import tech.pegasys.teku.validator.client.doppelganger.DoppelgangerDetector;
import tech.pegasys.teku.validator.client.duties.BeaconCommitteeSubscriptions;
import tech.pegasys.teku.validator.client.duties.BlockDutyFactory;
import tech.pegasys.teku.validator.client.duties.SlotBasedScheduledDuties;
import tech.pegasys.teku.validator.client.duties.attestations.AttestationDutyFactory;
import tech.pegasys.teku.validator.client.duties.synccommittee.ChainHeadTracker;
import tech.pegasys.teku.validator.client.duties.synccommittee.SyncCommitteeScheduledDuties;
import tech.pegasys.teku.validator.client.loader.OwnedValidators;
import tech.pegasys.teku.validator.client.loader.PublicKeyLoader;
import tech.pegasys.teku.validator.client.loader.SlashingProtectionLogger;
import tech.pegasys.teku.validator.client.loader.ValidatorLoader;
import tech.pegasys.teku.validator.client.proposerconfig.ProposerConfigProvider;
import tech.pegasys.teku.validator.client.proposerconfig.loader.ProposerConfigLoader;
import tech.pegasys.teku.validator.client.restapi.ValidatorRestApi;
import tech.pegasys.teku.validator.client.restapi.ValidatorRestApiConfig;
import tech.pegasys.teku.validator.eventadapter.InProcessBeaconNodeApi;
import tech.pegasys.teku.validator.remote.RemoteBeaconNodeApi;
import tech.pegasys.teku.validator.remote.sentry.SentryBeaconNodeApi;
import tech.pegasys.teku.validator.remote.sentry.SentryNodesConfig;
import tech.pegasys.teku.validator.remote.sentry.SentryNodesConfigLoader;

public class ValidatorClientService extends Service {

  private static final Logger LOG = LogManager.getLogger();
  private static final Duration DOPPELGANGER_DETECTOR_CHECK_DELAY = Duration.ofSeconds(5);
  private static final Duration DOPPELGANGER_DETECTOR_TIMEOUT = Duration.ofMinutes(15);
  private static final int DOPPELGANGER_DETECTOR_MAX_EPOCHS = 2;
  private final EventChannels eventChannels;
  private final ValidatorLoader validatorLoader;
  private final BeaconNodeApi beaconNodeApi;
  private final ForkProvider forkProvider;
  private final Spec spec;

  private final List<ValidatorTimingChannel> validatorTimingChannels = new ArrayList<>();
  private ValidatorStatusLogger validatorStatusLogger;
  private ValidatorIndexProvider validatorIndexProvider;
  private Optional<DoppelgangerDetector> maybeDoppelgangerDetector = Optional.empty();
  private final DoppelgangerDetectionAction doppelgangerDetectionAction;
  private Optional<RestApi> maybeValidatorRestApi = Optional.empty();
  private final Optional<BeaconProposerPreparer> beaconProposerPreparer;
  private final Optional<ValidatorRegistrator> validatorRegistrator;

  private final Optional<ProposerConfigManager> proposerConfigManager;
  private final SafeFuture<Void> initializationComplete = new SafeFuture<>();

  private final MetricsSystem metricsSystem;

  private ValidatorClientService(
      final EventChannels eventChannels,
      final ValidatorLoader validatorLoader,
      final BeaconNodeApi beaconNodeApi,
      final ForkProvider forkProvider,
      final Optional<ProposerConfigManager> proposerConfigManager,
      final Optional<BeaconProposerPreparer> beaconProposerPreparer,
      final Optional<ValidatorRegistrator> validatorRegistrator,
      final Spec spec,
      final MetricsSystem metricsSystem,
      final DoppelgangerDetectionAction doppelgangerDetectionAction) {
    this.eventChannels = eventChannels;
    this.validatorLoader = validatorLoader;
    this.beaconNodeApi = beaconNodeApi;
    this.forkProvider = forkProvider;
    this.proposerConfigManager = proposerConfigManager;
    this.beaconProposerPreparer = beaconProposerPreparer;
    this.validatorRegistrator = validatorRegistrator;
    this.spec = spec;
    this.metricsSystem = metricsSystem;
    this.doppelgangerDetectionAction = doppelgangerDetectionAction;
  }

  public static ValidatorClientService create(
      final ServiceConfig services,
      final ValidatorClientConfiguration config,
      final DoppelgangerDetectionAction doppelgangerDetectionAction) {
    final EventChannels eventChannels = services.getEventChannels();
    final ValidatorConfig validatorConfig = config.getValidatorConfig();

    final AsyncRunner asyncRunner =
        services.createAsyncRunnerWithMaxQueueSize(
            "validator", validatorConfig.getExecutorMaxQueueSize());

    final BeaconNodeApi beaconNodeApi = createBeaconNodeApi(services, config, asyncRunner);

    final ValidatorApiChannel validatorApiChannel = beaconNodeApi.getValidatorApi();
    final GenesisDataProvider genesisDataProvider =
        new GenesisDataProvider(asyncRunner, validatorApiChannel);
    final ForkProvider forkProvider = new ForkProvider(config.getSpec(), genesisDataProvider);

    final ValidatorLoader validatorLoader = createValidatorLoader(services, config, asyncRunner);
    final ValidatorRestApiConfig validatorApiConfig = config.getValidatorRestApiConfig();
    final Optional<ProposerConfigManager> proposerConfigManager;
    Optional<BeaconProposerPreparer> beaconProposerPreparer = Optional.empty();
    Optional<ValidatorRegistrator> validatorRegistrator = Optional.empty();
    if (config.getSpec().isMilestoneSupported(SpecMilestone.BELLATRIX)) {

      final ProposerConfigProvider proposerConfigProvider =
          ProposerConfigProvider.create(
              asyncRunner,
              validatorConfig.getRefreshProposerConfigFromSource(),
              new ProposerConfigLoader(new JsonProvider().getObjectMapper()),
              services.getTimeProvider(),
              validatorConfig.getProposerConfigSource());

      proposerConfigManager =
          Optional.of(
              new ProposerConfigManager(
                  validatorConfig,
                  new RuntimeProposerConfig(
                      Optional.of(
                          ValidatorClientService.getKeyManagerPath(services.getDataDirLayout())
                              .resolve("api-proposer-config.json"))),
                  proposerConfigProvider));

      beaconProposerPreparer =
          Optional.of(
              new BeaconProposerPreparer(
                  validatorApiChannel,
                  Optional.empty(),
                  proposerConfigManager.get(),
                  config.getSpec()));

      validatorRegistrator =
          Optional.of(
              new ValidatorRegistrator(
                  config.getSpec(),
                  services.getTimeProvider(),
                  validatorLoader.getOwnedValidators(),
                  proposerConfigManager.get(),
                  new ValidatorRegistrationBatchSender(
                      validatorConfig.getBuilderRegistrationSendingBatchSize(),
                      validatorApiChannel)));
    } else {
      proposerConfigManager = Optional.empty();
    }

    ValidatorClientService validatorClientService =
        new ValidatorClientService(
            eventChannels,
            validatorLoader,
            beaconNodeApi,
            forkProvider,
            proposerConfigManager,
            beaconProposerPreparer,
            validatorRegistrator,
            config.getSpec(),
            services.getMetricsSystem(),
            doppelgangerDetectionAction);

    validatorClientService.initializeValidators(validatorApiChannel, asyncRunner);

    asyncRunner
        .runAsync(
            () -> validatorClientService.initializeValidators(validatorApiChannel, asyncRunner))
        .thenCompose(
            __ -> {
              if (validatorConfig.isDoppelgangerDetectionEnabled()) {
                validatorClientService.initializeDoppelgangerDetector(
                    asyncRunner,
                    validatorApiChannel,
                    validatorClientService.spec,
                    services.getTimeProvider(),
                    genesisDataProvider);
              }
              return SafeFuture.COMPLETE;
            })
        .thenCompose(
            __ -> {
              if (validatorApiConfig.isRestApiEnabled()) {
                validatorClientService.initializeValidatorRestApi(
                    validatorApiConfig,
                    proposerConfigManager,
                    new ActiveKeyManager(
                        validatorLoader,
                        services.getEventChannels().getPublisher(ValidatorTimingChannel.class)),
                    services.getDataDirLayout(),
                    validatorClientService.maybeDoppelgangerDetector);
              } else {
                LOG.info("validator-api-enabled is false, not starting rest api.");
              }
              return SafeFuture.COMPLETE;
            })
        .thenCompose(
            __ -> {
              asyncRunner
                  .runAsync(
                      () ->
                          validatorClientService.scheduleValidatorsDuties(
                              config, validatorApiChannel, asyncRunner))
                  .propagateTo(validatorClientService.initializationComplete);
              return SafeFuture.COMPLETE;
            })
        .ifExceptionGetsHereRaiseABug();

    return validatorClientService;
  }

  private void initializeDoppelgangerDetector(
      AsyncRunner asyncRunner,
      ValidatorApiChannel validatorApiChannel,
      Spec spec,
      TimeProvider timeProvider,
      GenesisDataProvider genesisDataProvider) {
    DoppelgangerDetector doppelgangerDetector =
        new DoppelgangerDetector(
            asyncRunner,
            validatorApiChannel,
            spec,
            timeProvider,
            genesisDataProvider,
            DOPPELGANGER_DETECTOR_CHECK_DELAY,
            DOPPELGANGER_DETECTOR_TIMEOUT,
            DOPPELGANGER_DETECTOR_MAX_EPOCHS);
    maybeDoppelgangerDetector = Optional.of(doppelgangerDetector);
  }

  private void initializeValidatorRestApi(
      final ValidatorRestApiConfig validatorRestApiConfig,
      final Optional<ProposerConfigManager> proposerConfigManager,
      final ActiveKeyManager activeKeyManager,
      final DataDirLayout dataDirLayout,
      final Optional<DoppelgangerDetector> maybeDoppelgangerDetector) {
    final RestApi validatorRestApi =
        ValidatorRestApi.create(
            validatorRestApiConfig,
            proposerConfigManager,
            activeKeyManager,
            dataDirLayout,
            maybeDoppelgangerDetector,
            new DoppelgangerDetectionAlert());
    maybeValidatorRestApi = Optional.of(validatorRestApi);
  }

  private static BeaconNodeApi createBeaconNodeApi(
      final ServiceConfig services,
      final ValidatorClientConfiguration validatorClientConfiguration,
      final AsyncRunner asyncRunner) {
    final ValidatorConfig validatorConfig = validatorClientConfiguration.getValidatorConfig();

    final BeaconNodeApi beaconNodeApi;
    if (validatorConfig.getSentryNodeConfigurationFile().isEmpty()) {
      beaconNodeApi =
          validatorConfig
              .getBeaconNodeApiEndpoints()
              .map(
                  beaconNodeApiEndpoints ->
                      RemoteBeaconNodeApi.create(
                          services,
                          validatorConfig,
                          asyncRunner,
                          validatorClientConfiguration.getSpec(),
                          beaconNodeApiEndpoints))
              .orElseGet(
                  () ->
                      InProcessBeaconNodeApi.create(
                          services,
                          validatorConfig,
                          asyncRunner,
                          validatorClientConfiguration.getSpec()));
    } else {
      final SentryNodesConfig sentryNodesConfig =
          new SentryNodesConfigLoader()
              .load(validatorConfig.getSentryNodeConfigurationFile().get());

      beaconNodeApi =
          SentryBeaconNodeApi.create(
              services,
              validatorConfig,
              asyncRunner,
              validatorClientConfiguration.getSpec(),
              sentryNodesConfig);
    }

    return beaconNodeApi;
  }

  private static ValidatorLoader createValidatorLoader(
      final ServiceConfig services,
      final ValidatorClientConfiguration config,
      final AsyncRunner asyncRunner) {
    final Path slashingProtectionPath = getSlashingProtectionPath(services.getDataDirLayout());
    final SlashingProtector slashingProtector =
        new LocalSlashingProtector(
            SyncDataAccessor.create(slashingProtectionPath), slashingProtectionPath);
    final SlashingProtectionLogger slashingProtectionLogger =
        new SlashingProtectionLogger(
            slashingProtector, config.getSpec(), asyncRunner, ValidatorLogger.VALIDATOR_LOGGER);
    return ValidatorLoader.create(
        config.getSpec(),
        config.getValidatorConfig(),
        config.getInteropConfig(),
        slashingProtector,
        slashingProtectionLogger,
        new PublicKeyLoader(),
        asyncRunner,
        services.getMetricsSystem(),
        config.getValidatorRestApiConfig().isRestApiEnabled()
            ? Optional.of(services.getDataDirLayout())
            : Optional.empty());
  }

  private void initializeValidators(
      final ValidatorApiChannel validatorApiChannel, final AsyncRunner asyncRunner) {
    validatorLoader.loadValidators();
    final OwnedValidators validators = validatorLoader.getOwnedValidators();
    this.validatorIndexProvider =
        new ValidatorIndexProvider(validators, validatorApiChannel, asyncRunner);
  }

  private void scheduleValidatorsDuties(
      ValidatorClientConfiguration config,
      ValidatorApiChannel validatorApiChannel,
      AsyncRunner asyncRunner) {
    final OwnedValidators validators = validatorLoader.getOwnedValidators();
    final BlockDutyFactory blockDutyFactory =
        new BlockDutyFactory(
            forkProvider,
            validatorApiChannel,
            config.getValidatorConfig().isBlindedBeaconBlocksEnabled(),
            spec);
    final AttestationDutyFactory attestationDutyFactory =
        new AttestationDutyFactory(spec, forkProvider, validatorApiChannel);
    final BeaconCommitteeSubscriptions beaconCommitteeSubscriptions =
        new BeaconCommitteeSubscriptions(validatorApiChannel);
    final DutyLoader<?> attestationDutyLoader =
        new RetryingDutyLoader<>(
            asyncRunner,
            new AttestationDutyLoader(
                validatorApiChannel,
                forkProvider,
                dependentRoot ->
                    new SlotBasedScheduledDuties<>(attestationDutyFactory, dependentRoot),
                validators,
                validatorIndexProvider,
                beaconCommitteeSubscriptions,
                spec));
    final DutyLoader<?> blockDutyLoader =
        new RetryingDutyLoader<>(
            asyncRunner,
            new BlockProductionDutyLoader(
                validatorApiChannel,
                dependentRoot -> new SlotBasedScheduledDuties<>(blockDutyFactory, dependentRoot),
                validators,
                validatorIndexProvider));
    validatorTimingChannels.add(new BlockDutyScheduler(metricsSystem, blockDutyLoader, spec));
    validatorTimingChannels.add(
        new AttestationDutyScheduler(metricsSystem, attestationDutyLoader, spec));
    validatorTimingChannels.add(validatorLoader.getSlashingProtectionLogger());

    if (spec.isMilestoneSupported(SpecMilestone.ALTAIR)) {
      final ChainHeadTracker chainHeadTracker = new ChainHeadTracker();
      validatorTimingChannels.add(chainHeadTracker);
      final DutyLoader<SyncCommitteeScheduledDuties> syncCommitteeDutyLoader =
          new RetryingDutyLoader<>(
              asyncRunner,
              new SyncCommitteeDutyLoader(
                  validators,
                  validatorIndexProvider,
                  spec,
                  validatorApiChannel,
                  chainHeadTracker,
                  forkProvider));
      validatorTimingChannels.add(
          new SyncCommitteeScheduler(
              metricsSystem, spec, syncCommitteeDutyLoader, new Random()::nextInt));
    }

    if (spec.isMilestoneSupported(SpecMilestone.BELLATRIX)) {
      beaconProposerPreparer.ifPresent(
          preparer -> {
            preparer.initialize(Optional.of(validatorIndexProvider));
            validatorTimingChannels.add(preparer);
          });
      validatorRegistrator.ifPresent(validatorTimingChannels::add);
    }
    addValidatorCountMetric(metricsSystem, validators);
    this.validatorStatusLogger =
        new DefaultValidatorStatusLogger(
            metricsSystem, validators, validatorApiChannel, asyncRunner);
  }

  public static Path getSlashingProtectionPath(final DataDirLayout dataDirLayout) {
    return dataDirLayout.getValidatorDataDirectory().resolve("slashprotection");
  }

  public static Path getManagedLocalKeystorePath(final DataDirLayout dataDirLayout) {
    return getKeyManagerPath(dataDirLayout).resolve("local");
  }

  public static Path getManagedLocalKeystorePasswordPath(final DataDirLayout dataDirLayout) {
    return getKeyManagerPath(dataDirLayout).resolve("local-passwords");
  }

  public static Path getManagedRemoteKeyPath(final DataDirLayout dataDirLayout) {
    return getKeyManagerPath(dataDirLayout).resolve("remote");
  }

  public static Path getKeyManagerPath(final DataDirLayout dataDirLayout) {
    return dataDirLayout.getValidatorDataDirectory().resolve("key-manager");
  }

  private static void addValidatorCountMetric(
      final MetricsSystem metricsSystem, final OwnedValidators validators) {
    metricsSystem.createIntegerGauge(
        TekuMetricCategory.VALIDATOR,
        "local_validator_count",
        "Current number of validators running in this validator client",
        validators::getValidatorCount);
  }

  @Override
  protected SafeFuture<?> doStart() {
    return initializationComplete
        .thenCompose(
            __ ->
                proposerConfigManager
                    .map(manager -> manager.initialize(validatorLoader.getOwnedValidators()))
                    .orElse(SafeFuture.COMPLETE))
        .thenCompose(
            __ -> {
              maybeValidatorRestApi.ifPresent(
                  restApi -> restApi.start().ifExceptionGetsHereRaiseABug());
              SystemSignalListener.registerReloadConfigListener(validatorLoader::loadValidators);
              validatorIndexProvider.lookupValidators();
              return maybeDoppelgangerDetector
                  .map(
                      doppelgangerDetector ->
                          doppelgangerDetector
                              .performDoppelgangerDetection(
                                  validatorLoader.getOwnedValidators().getPublicKeys())
                              .thenAccept(
                                  doppelgangerDetected -> {
                                    if (!doppelgangerDetected.isEmpty()) {
                                      doppelgangerDetectionAction.shutDown();
                                    }
                                  }))
                  .orElse(SafeFuture.COMPLETE);
            })
        .thenCompose(
            __ -> {
              eventChannels.subscribe(
                  ValidatorTimingChannel.class,
                  new ValidatorTimingActions(
                      validatorStatusLogger,
                      validatorIndexProvider,
                      validatorTimingChannels,
                      spec,
                      metricsSystem));
              validatorStatusLogger.printInitialValidatorStatuses().ifExceptionGetsHereRaiseABug();
              return beaconNodeApi.subscribeToEvents();
            });
  }

  @Override
  protected SafeFuture<?> doStop() {
    return SafeFuture.allOf(
        SafeFuture.fromRunnable(
            () ->
                maybeValidatorRestApi.ifPresent(
                    restApi -> restApi.stop().ifExceptionGetsHereRaiseABug())),
        beaconNodeApi.unsubscribeFromEvents());
  }
}
