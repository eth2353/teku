/*
 * Copyright Consensys Software Inc., 2022
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

package tech.pegasys.teku.api;

import static com.google.common.base.Preconditions.checkNotNull;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes32;
import org.apache.tuweni.units.bigints.UInt256;
import tech.pegasys.teku.api.exceptions.BadRequestException;
import tech.pegasys.teku.api.schema.SignedBeaconBlock;
import tech.pegasys.teku.api.schema.ValidatorBlockResult;
import tech.pegasys.teku.api.schema.altair.SignedBeaconBlockAltair;
import tech.pegasys.teku.api.schema.bellatrix.SignedBeaconBlockBellatrix;
import tech.pegasys.teku.api.schema.bellatrix.SignedBlindedBeaconBlockBellatrix;
import tech.pegasys.teku.api.schema.capella.SignedBeaconBlockCapella;
import tech.pegasys.teku.api.schema.capella.SignedBlindedBeaconBlockCapella;
import tech.pegasys.teku.api.schema.deneb.SignedBeaconBlockDeneb;
import tech.pegasys.teku.api.schema.deneb.SignedBlindedBeaconBlockDeneb;
import tech.pegasys.teku.api.schema.phase0.SignedBeaconBlockPhase0;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.ssz.SszList;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.provider.JsonProvider;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecMilestone;
import tech.pegasys.teku.spec.constants.EthConstants;
import tech.pegasys.teku.spec.datastructures.blocks.BlockContainer;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainer;
import tech.pegasys.teku.spec.datastructures.builder.SignedValidatorRegistration;
import tech.pegasys.teku.spec.datastructures.execution.ExecutionPayloadResult;
import tech.pegasys.teku.spec.datastructures.metadata.BlockContainerAndMetaData;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttestationData;
import tech.pegasys.teku.spec.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SignedContributionAndProof;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncCommitteeContribution;
import tech.pegasys.teku.spec.datastructures.operations.versions.altair.SyncCommitteeMessage;
import tech.pegasys.teku.spec.datastructures.operations.versions.bellatrix.BeaconPreparableProposer;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.executionlayer.ExecutionLayerBlockProductionManager;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult.FailureReason;
import tech.pegasys.teku.spec.schemas.SchemaDefinitionsAltair;
import tech.pegasys.teku.storage.client.ChainDataUnavailableException;
import tech.pegasys.teku.storage.client.CombinedChainDataClient;
import tech.pegasys.teku.validator.api.AttesterDuties;
import tech.pegasys.teku.validator.api.CommitteeSubscriptionRequest;
import tech.pegasys.teku.validator.api.ProposerDuties;
import tech.pegasys.teku.validator.api.SendSignedBlockResult;
import tech.pegasys.teku.validator.api.SubmitDataError;
import tech.pegasys.teku.validator.api.SyncCommitteeDuties;
import tech.pegasys.teku.validator.api.SyncCommitteeSubnetSubscription;
import tech.pegasys.teku.validator.api.ValidatorApiChannel;

public class ValidatorDataProvider {

  private static final Logger LOG = LogManager.getLogger();
  public static final String CANNOT_PRODUCE_HISTORIC_BLOCK =
      "Cannot produce a block for a historic slot.";
  public static final String NO_SLOT_PROVIDED = "No slot was provided.";
  public static final String NO_RANDAO_PROVIDED = "No randao_reveal was provided.";
  public static final String PARTIAL_PUBLISH_FAILURE_MESSAGE =
      "Some items failed to publish, refer to errors for details";
  private final ValidatorApiChannel validatorApiChannel;
  private final CombinedChainDataClient combinedChainDataClient;

  private static final int SC_INTERNAL_ERROR = 500;
  private static final int SC_ACCEPTED = 202;
  private static final int SC_OK = 200;
  private final Spec spec;

  private final ExecutionLayerBlockProductionManager executionLayerBlockProductionManager;
  private final RewardCalculator rewardCalculator;

  public ValidatorDataProvider(
      final Spec spec,
      final ValidatorApiChannel validatorApiChannel,
      final CombinedChainDataClient combinedChainDataClient,
      final ExecutionLayerBlockProductionManager executionLayerBlockProductionManager,
      final RewardCalculator rewardCalculator) {
    this.validatorApiChannel = validatorApiChannel;
    this.combinedChainDataClient = combinedChainDataClient;
    this.executionLayerBlockProductionManager = executionLayerBlockProductionManager;
    this.spec = spec;
    this.rewardCalculator = rewardCalculator;
  }

  public boolean isStoreAvailable() {
    return combinedChainDataClient.isStoreAvailable();
  }

  public SafeFuture<Optional<BlockContainer>> getUnsignedBeaconBlockAtSlot(
      final UInt64 slot,
      final BLSSignature randao,
      final Optional<Bytes32> graffiti,
      final boolean isBlinded) {
    checkBlockProducingParameters(slot, randao);
    return validatorApiChannel.createUnsignedBlock(slot, randao, graffiti, isBlinded);
  }

  public SafeFuture<Optional<BlockContainerAndMetaData<BlockContainer>>> produceBlock(
      final UInt64 slot, final BLSSignature randao, final Optional<Bytes32> graffiti) {
    checkBlockProducingParameters(slot, randao);
    return validatorApiChannel
        .createUnsignedBlock(slot, randao, graffiti)
        .thenCompose(this::lookUpBlockValues);
  }

  private SafeFuture<Optional<BlockContainerAndMetaData<BlockContainer>>> lookUpBlockValues(
      final Optional<BlockContainer> maybeBlockContainer) {
    return maybeBlockContainer
        .map(
            blockContainer ->
                retrieveExecutionPayloadValue(maybeBlockContainer.get().getSlot())
                    .thenCombine(
                        retrieveConsensusBlockRewards(maybeBlockContainer.get()),
                        (executionPayloadValue, consensusBlockValue) ->
                            addMetaData(
                                maybeBlockContainer, executionPayloadValue, consensusBlockValue)))
        .orElse(SafeFuture.completedFuture(Optional.empty()));
  }

  private Optional<BlockContainerAndMetaData<BlockContainer>> addMetaData(
      final Optional<BlockContainer> maybeBlockContainer,
      final UInt256 executionPayloadValue,
      final UInt256 consensusBlockValue) {
    return maybeBlockContainer.map(
        blockContainer ->
            new BlockContainerAndMetaData<>(
                blockContainer,
                spec.atSlot(blockContainer.getSlot()).getMilestone(),
                executionPayloadValue,
                consensusBlockValue));
  }

  private SafeFuture<UInt256> retrieveExecutionPayloadValue(final UInt64 slot) {
    final Optional<ExecutionPayloadResult> cachedPayloadResult =
        executionLayerBlockProductionManager.getCachedPayloadResult(slot);
    if (cachedPayloadResult.isEmpty()) {
      LOG.warn(
          "Unable to get cached payload result for slot {}. Setting execution payload value to 0",
          slot.intValue());
      return SafeFuture.completedFuture(UInt256.ZERO);
    }
    final Optional<SafeFuture<UInt256>> executionPayloadValueFuture =
        cachedPayloadResult.get().getExecutionPayloadValueFuture();
    if (executionPayloadValueFuture.isEmpty()) {
      LOG.warn(
          "No execution payload value available for slot {}. Setting value to 0", slot.intValue());
      return SafeFuture.completedFuture(UInt256.ZERO);
    }
    return executionPayloadValueFuture.get();
  }

  private SafeFuture<UInt256> retrieveConsensusBlockRewards(final BlockContainer blockContainer) {
    final String rewardCalculationError =
        String.format(
            "Unable to calculate block rewards for slot %d. Setting value to 0",
            blockContainer.getSlot().intValue());
    return combinedChainDataClient
        .getStateAtSlotExact(blockContainer.getBlock().getSlot().decrement())
        .thenApply(
            maybeParentState ->
                maybeParentState.map(
                    parentState ->
                        rewardCalculator.getBlockRewardData(blockContainer, parentState)))
        .thenApply(
            blockRewardData ->
                blockRewardData.map(
                    rewardData -> EthConstants.GWEI_TO_WEI.multiply(rewardData.getTotal())))
        .thenApply(
            maybeTotalRewards -> {
              if (maybeTotalRewards.isEmpty()) {
                LOG.warn(rewardCalculationError);
                return UInt256.ZERO;
              } else {
                return maybeTotalRewards.get();
              }
            })
        .exceptionally(
            throwable -> {
              LOG.warn(rewardCalculationError, throwable);
              return UInt256.ZERO;
            });
  }

  private void checkBlockProducingParameters(final UInt64 slot, final BLSSignature randao) {
    if (slot == null) {
      throw new IllegalArgumentException(NO_SLOT_PROVIDED);
    }
    if (randao == null) {
      throw new IllegalArgumentException(NO_RANDAO_PROVIDED);
    }
    final int slotsPerEpoch = spec.atSlot(slot).getConfig().getSlotsPerEpoch();
    final UInt64 currentSlot = combinedChainDataClient.getCurrentSlot();
    if (currentSlot.plus(slotsPerEpoch).isLessThan(slot)) {
      throw new IllegalArgumentException(
          "Cannot produce a block more than " + slotsPerEpoch + " slots in the future.");
    }
    if (currentSlot.isGreaterThan(slot)) {
      throw new IllegalArgumentException(CANNOT_PRODUCE_HISTORIC_BLOCK);
    }
  }

  public SafeFuture<Optional<BlockContainer>> getUnsignedBeaconBlockAtSlot(
      UInt64 slot, BLSSignature randao, Optional<Bytes32> graffiti) {
    if (randao == null) {
      throw new IllegalArgumentException(NO_RANDAO_PROVIDED);
    }
    return getUnsignedBeaconBlockAtSlot(
        slot, BLSSignature.fromBytesCompressed(randao.toSSZBytes()), graffiti, false);
  }

  public SpecMilestone getMilestoneAtSlot(final UInt64 slot) {
    return spec.atSlot(slot).getMilestone();
  }

  public SafeFuture<Optional<AttestationData>> createAttestationDataAtSlot(
      UInt64 slot, int committeeIndex) {
    if (!isStoreAvailable()) {
      return SafeFuture.failedFuture(new ChainDataUnavailableException());
    }
    return validatorApiChannel
        .createAttestationData(slot, committeeIndex)
        .thenApply(maybeAttestation -> maybeAttestation)
        .exceptionallyCompose(
            error -> {
              final Throwable rootCause = Throwables.getRootCause(error);
              if (rootCause instanceof IllegalArgumentException) {
                return SafeFuture.failedFuture(new BadRequestException(rootCause.getMessage()));
              }
              return SafeFuture.failedFuture(error);
            });
  }

  public SafeFuture<List<SubmitDataError>> submitAttestations(List<Attestation> attestations) {
    return validatorApiChannel.sendSignedAttestations(attestations);
  }

  public SignedBeaconBlock parseBlock(final JsonProvider jsonProvider, final String jsonBlock)
      throws JsonProcessingException {
    final ObjectMapper mapper = jsonProvider.getObjectMapper();
    final JsonNode jsonNode = mapper.readTree(jsonBlock);
    final UInt64 slot = mapper.treeToValue(jsonNode.findValue("slot"), UInt64.class);
    final SignedBeaconBlock signedBeaconBlock;
    checkNotNull(slot, "Slot was not found in json block");
    switch (spec.atSlot(slot).getMilestone()) {
      case PHASE0:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockPhase0.class);
        break;
      case ALTAIR:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockAltair.class);
        break;
      case BELLATRIX:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockBellatrix.class);
        break;
      case CAPELLA:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockCapella.class);
        break;
      case DENEB:
        signedBeaconBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockDeneb.class);
        break;
      default:
        throw new IllegalArgumentException("Could not determine milestone for slot " + slot);
    }
    return signedBeaconBlock;
  }

  public SignedBeaconBlock parseBlindedBlock(
      final JsonProvider jsonProvider, final String jsonBlock) throws JsonProcessingException {
    final ObjectMapper mapper = jsonProvider.getObjectMapper();
    final JsonNode jsonNode = mapper.readTree(jsonBlock);
    final UInt64 slot = mapper.treeToValue(jsonNode.findValue("slot"), UInt64.class);
    final SignedBeaconBlock signedBlindedBlock;
    checkNotNull(slot, "Slot was not found in json block");
    switch (spec.atSlot(slot).getMilestone()) {
      case PHASE0:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockPhase0.class);
        break;
      case ALTAIR:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBeaconBlockAltair.class);
        break;
      case BELLATRIX:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBlindedBeaconBlockBellatrix.class);
        break;
      case CAPELLA:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBlindedBeaconBlockCapella.class);
        break;
      case DENEB:
        signedBlindedBlock = mapper.treeToValue(jsonNode, SignedBlindedBeaconBlockDeneb.class);
        break;
      default:
        throw new IllegalArgumentException("Could not determine milestone for slot " + slot);
    }
    return signedBlindedBlock;
  }

  public SafeFuture<ValidatorBlockResult> submitSignedBlock(
      final SignedBeaconBlock signedBeaconBlock,
      final BroadcastValidationLevel broadcastValidationLevel) {
    return submitSignedBlock(
            signedBeaconBlock.asInternalSignedBeaconBlock(spec), broadcastValidationLevel)
        .thenApply(ValidatorDataProvider::generateSubmitSignedBlockResponse);
  }

  public SafeFuture<SendSignedBlockResult> submitSignedBlock(
      final SignedBlockContainer signedBlockContainer,
      final BroadcastValidationLevel broadcastValidationLevel) {
    return validatorApiChannel.sendSignedBlock(signedBlockContainer, broadcastValidationLevel);
  }

  public SafeFuture<SendSignedBlockResult> submitSignedBlindedBlock(
      final SignedBlockContainer signedBlindedBlockContainer,
      final BroadcastValidationLevel broadcastValidationLevel) {
    return validatorApiChannel.sendSignedBlock(
        signedBlindedBlockContainer, broadcastValidationLevel);
  }

  public SafeFuture<List<SubmitDataError>> submitCommitteeSignatures(
      final List<SyncCommitteeMessage> messages) {
    return validatorApiChannel.sendSyncCommitteeMessages(
        messages.stream()
            .flatMap(message -> checkInternalCommitteeSignature(message).stream())
            .toList());
  }

  private Optional<SyncCommitteeMessage> checkInternalCommitteeSignature(
      final SyncCommitteeMessage message) {
    final Optional<SchemaDefinitionsAltair> schema =
        spec.atSlot(message.getSlot()).getSchemaDefinitions().toVersionAltair();
    if (schema.isEmpty()) {
      final String errorMessage =
          String.format(
              "Could not create sync committee signature at phase0 slot %s for validator %s",
              message.getSlot(), message.getValidatorIndex());
      throw new IllegalArgumentException(errorMessage);
    }
    return Optional.of(message);
  }

  public SafeFuture<Optional<Attestation>> createAggregate(
      final UInt64 slot, final Bytes32 attestationHashTreeRoot) {
    return validatorApiChannel.createAggregate(slot, attestationHashTreeRoot);
  }

  public SafeFuture<List<SubmitDataError>> sendAggregateAndProofs(
      final List<SignedAggregateAndProof> aggregateAndProofs) {
    return validatorApiChannel.sendAggregateAndProofs(aggregateAndProofs);
  }

  public SafeFuture<Void> subscribeToBeaconCommittee(
      final List<CommitteeSubscriptionRequest> requests) {
    return validatorApiChannel.subscribeToBeaconCommittee(requests);
  }

  public SafeFuture<Void> subscribeToSyncCommitteeSubnets(
      final Collection<SyncCommitteeSubnetSubscription> subscriptions) {
    return validatorApiChannel.subscribeToSyncCommitteeSubnets(subscriptions);
  }

  public SafeFuture<Optional<AttesterDuties>> getAttesterDuties(
      final UInt64 epoch, final IntList indices) {
    return SafeFuture.of(() -> validatorApiChannel.getAttestationDuties(epoch, indices));
  }

  public SafeFuture<Optional<ProposerDuties>> getProposerDuties(final UInt64 epoch) {
    return SafeFuture.of(() -> validatorApiChannel.getProposerDuties(epoch));
  }

  public SafeFuture<Optional<SyncCommitteeContribution>> createSyncCommitteeContribution(
      final UInt64 slot, final int subcommitteeIndex, final Bytes32 blockRoot) {
    return validatorApiChannel.createSyncCommitteeContribution(slot, subcommitteeIndex, blockRoot);
  }

  public SafeFuture<Optional<SyncCommitteeDuties>> getSyncDuties(
      final UInt64 epoch, final IntList indices) {
    return SafeFuture.of(() -> validatorApiChannel.getSyncCommitteeDuties(epoch, indices));
  }

  public SafeFuture<Void> sendContributionAndProofs(
      final List<SignedContributionAndProof> contributionAndProofs) {
    return validatorApiChannel.sendSignedContributionAndProofs(contributionAndProofs);
  }

  public SafeFuture<Void> prepareBeaconProposer(
      final List<BeaconPreparableProposer> beaconPreparableProposers) {
    return validatorApiChannel.prepareBeaconProposer(beaconPreparableProposers);
  }

  public SafeFuture<Void> registerValidators(
      final SszList<SignedValidatorRegistration> validatorRegistrations) {
    return validatorApiChannel
        .getValidatorStatuses(
            validatorRegistrations.stream()
                .map(registration -> registration.getMessage().getPublicKey())
                .toList())
        .thenComposeChecked(
            maybeValidatorStatuses -> {
              if (maybeValidatorStatuses.isEmpty()) {
                final String errorMessage =
                    "Couldn't retrieve validator statuses during registering. Most likely the BN is still syncing.";
                return SafeFuture.failedFuture(new IllegalStateException(errorMessage));
              }

              final List<SignedValidatorRegistration> activeAndPendingValidatorRegistrations =
                  validatorRegistrations.stream()
                      .filter(
                          registration ->
                              Optional.ofNullable(
                                      maybeValidatorStatuses
                                          .get()
                                          .get(registration.getMessage().getPublicKey()))
                                  .map(status -> !status.hasExited())
                                  .orElse(false))
                      .toList();

              return validatorApiChannel.registerValidators(
                  validatorRegistrations
                      .getSchema()
                      .createFromElements(activeAndPendingValidatorRegistrations));
            });
  }

  public boolean isPhase0Slot(final UInt64 slot) {
    return spec.atSlot(slot).getMilestone() == SpecMilestone.PHASE0;
  }

  private static ValidatorBlockResult generateSubmitSignedBlockResponse(
      SendSignedBlockResult result) {
    int responseCode;
    Optional<Bytes32> hashRoot = result.getBlockRoot();
    if (result.getRejectionReason().isEmpty()) {
      responseCode = SC_OK;
    } else if (result.getRejectionReason().get().equals(FailureReason.INTERNAL_ERROR.name())) {
      responseCode = SC_INTERNAL_ERROR;
    } else {
      responseCode = SC_ACCEPTED;
    }

    return new ValidatorBlockResult(responseCode, result.getRejectionReason(), hashRoot);
  }
}
