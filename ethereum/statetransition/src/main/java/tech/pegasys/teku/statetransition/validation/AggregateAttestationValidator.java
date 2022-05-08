/*
 * Copyright 2020 ConsenSys AG.
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

package tech.pegasys.teku.statetransition.validation;

import static java.lang.Math.toIntExact;
import static tech.pegasys.teku.infrastructure.async.SafeFuture.completedFuture;
import static tech.pegasys.teku.spec.config.Constants.VALID_AGGREGATE_SET_SIZE;
import static tech.pegasys.teku.spec.config.Constants.VALID_ATTESTATION_DATA_SET_SIZE;
import static tech.pegasys.teku.spec.constants.ValidatorConstants.TARGET_AGGREGATORS_PER_COMMITTEE;
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.ignore;
import static tech.pegasys.teku.statetransition.validation.InternalValidationResult.reject;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSPublicKey;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.bls.BLSSignatureVerifier;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.infrastructure.collections.LimitedSet;
import tech.pegasys.teku.infrastructure.ssz.collections.SszBitlist;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.SpecVersion;
import tech.pegasys.teku.spec.constants.Domain;
import tech.pegasys.teku.spec.datastructures.attestation.ValidateableAttestation;
import tech.pegasys.teku.spec.datastructures.operations.AggregateAndProof;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.SignedAggregateAndProof;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.util.AsyncBLSSignatureVerifier;
import tech.pegasys.teku.spec.logic.common.util.AsyncBatchBLSSignatureVerifier;
import tech.pegasys.teku.statetransition.util.SeenAggregatesCache;

public class AggregateAttestationValidator {
  private static final Logger LOG = LogManager.getLogger();
  private final Set<AggregatorIndexAndEpoch> receivedAggregatorIndexAndEpochs =
      LimitedSet.create(VALID_AGGREGATE_SET_SIZE);
  private final SeenAggregatesCache<Bytes32> seenAggregationBits =
      new SeenAggregatesCache<>(VALID_ATTESTATION_DATA_SET_SIZE, TARGET_AGGREGATORS_PER_COMMITTEE);
  private final AttestationValidator attestationValidator;
  private final Spec spec;
  private final AsyncBLSSignatureVerifier signatureVerifier;

  public AggregateAttestationValidator(
      final Spec spec,
      final AttestationValidator attestationValidator,
      final AsyncBLSSignatureVerifier signatureVerifier) {
    this.attestationValidator = attestationValidator;
    this.spec = spec;
    this.signatureVerifier = signatureVerifier;
  }

  public void addSeenAggregate(final ValidateableAttestation attestation) {
    seenAggregationBits.add(
        attestation.getData().hashTreeRoot(), attestation.getAttestation().getAggregationBits());
  }

  public SafeFuture<InternalValidationResult> validate(final ValidateableAttestation attestation) {
    final SignedAggregateAndProof signedAggregate = attestation.getSignedAggregateAndProof();
    final AggregateAndProof aggregateAndProof = signedAggregate.getMessage();
    final Attestation aggregate = aggregateAndProof.getAggregate();
    final UInt64 aggregateSlot = aggregate.getData().getSlot();
    final SpecVersion specVersion = spec.atSlot(aggregateSlot);

    final AggregatorIndexAndEpoch aggregatorIndexAndEpoch =
        new AggregatorIndexAndEpoch(
            aggregateAndProof.getIndex(), spec.computeEpochAtSlot(aggregateSlot));
    if (receivedAggregatorIndexAndEpochs.contains(aggregatorIndexAndEpoch)) {
      return completedFuture(ignore("Ignoring duplicate aggregate"));
    }

    final SszBitlist aggregationBits = attestation.getAttestation().getAggregationBits();
    if (seenAggregationBits.isAlreadySeen(attestation.getData().hashTreeRoot(), aggregationBits)) {
      return completedFuture(ignore("Ignoring duplicate aggregate based on aggregation bits"));
    }

    final AsyncBatchBLSSignatureVerifier signatureVerifier =
        new AsyncBatchBLSSignatureVerifier(this.signatureVerifier);
    return singleOrAggregateAttestationChecks(signatureVerifier, attestation, OptionalInt.empty())
        .thenCompose(
            resultWithState -> {
              if (resultWithState.getResult().isNotProcessable()) {
                LOG.trace("Rejecting aggregate because attestation failed validation");
                return completedFuture(resultWithState.getResult());
              }

              final Optional<BeaconState> maybeState = resultWithState.getState();
              if (maybeState.isEmpty()) {
                // State isn't yet available. We've already handled ignore and reject conditions
                // as not processable above so need to save for when the state is available
                LOG.trace("Saving aggregate for future because state is not yet available");
                return SafeFuture.completedFuture(InternalValidationResult.SAVE_FOR_FUTURE);
              }

              final BeaconState state = maybeState.get();

              final Optional<BLSPublicKey> aggregatorPublicKey =
                  spec.getValidatorPubKey(state, aggregateAndProof.getIndex());
              if (aggregatorPublicKey.isEmpty()) {
                return SafeFuture.completedFuture(reject("Rejecting aggregate with invalid index"));
              }

              if (!isSelectionProofValid(
                  signatureVerifier,
                  aggregateSlot,
                  state,
                  aggregatorPublicKey.get(),
                  aggregateAndProof.getSelectionProof())) {
                return SafeFuture.completedFuture(
                    reject("Rejecting aggregate with incorrect selection proof"));
              }

              final IntList beaconCommittee =
                  spec.getBeaconCommittee(state, aggregateSlot, aggregate.getData().getIndex());

              final int aggregatorModulo =
                  specVersion.getValidatorsUtil().getAggregatorModulo(beaconCommittee.size());
              if (!specVersion
                  .getValidatorsUtil()
                  .isAggregator(aggregateAndProof.getSelectionProof(), aggregatorModulo)) {
                return SafeFuture.completedFuture(
                    reject(
                        "Rejecting aggregate because selection proof does not select validator as aggregator"));
              }
              if (!beaconCommittee.contains(toIntExact(aggregateAndProof.getIndex().longValue()))) {
                return SafeFuture.completedFuture(
                    reject(
                        "Rejecting aggregate because attester is not in committee. Should have been one of %s",
                        beaconCommittee));
              }

              if (!validateSignature(
                  signatureVerifier, signedAggregate, state, aggregatorPublicKey.get())) {
                return SafeFuture.completedFuture(
                    reject("Rejecting aggregate with invalid signature"));
              }

              return signatureVerifier
                  .batchVerify()
                  .thenApply(
                      signatureValid -> {
                        if (!signatureValid) {
                          return reject("Rejecting aggregate with invalid batch signature");
                        }

                        if (!receivedAggregatorIndexAndEpochs.add(aggregatorIndexAndEpoch)) {
                          return ignore("Ignoring duplicate aggregate");
                        }
                        if (!seenAggregationBits.add(
                            attestation.getData().hashTreeRoot(),
                            attestation.getAttestation().getAggregationBits())) {
                          return ignore("Ignoring duplicate aggregate based on aggregation bits");
                        }
                        return resultWithState.getResult();
                      });
            });
  }

  private boolean validateSignature(
      final AsyncBatchBLSSignatureVerifier signatureVerifier,
      final SignedAggregateAndProof signedAggregate,
      final BeaconState state,
      final BLSPublicKey aggregatorPublicKey) {
    final AggregateAndProof aggregateAndProof = signedAggregate.getMessage();
    final Bytes32 domain =
        spec.getDomain(
            Domain.AGGREGATE_AND_PROOF,
            spec.computeEpochAtSlot(aggregateAndProof.getAggregate().getData().getSlot()),
            state.getFork(),
            state.getGenesisValidatorsRoot());
    final Bytes signingRoot = spec.computeSigningRoot(aggregateAndProof, domain);
    return signatureVerifier.verify(
        aggregatorPublicKey, signingRoot, signedAggregate.getSignature());
  }

  private boolean isSelectionProofValid(
      final BLSSignatureVerifier signatureVerifier,
      final UInt64 aggregateSlot,
      final BeaconState state,
      final BLSPublicKey aggregatorPublicKey,
      final BLSSignature selectionProof) {
    final Bytes32 domain =
        spec.getDomain(
            Domain.SELECTION_PROOF,
            spec.computeEpochAtSlot(aggregateSlot),
            state.getFork(),
            state.getGenesisValidatorsRoot());
    final Bytes signingRoot = spec.computeSigningRoot(aggregateSlot, domain);
    return signatureVerifier.verify(aggregatorPublicKey, signingRoot, selectionProof);
  }

  SafeFuture<InternalValidationResultWithState> singleOrAggregateAttestationChecks(
      final AsyncBatchBLSSignatureVerifier signatureVerifier,
      final ValidateableAttestation validateableAttestation,
      final OptionalInt receivedOnSubnetId) {
    return attestationValidator.singleOrAggregateAttestationChecks(
        AsyncBLSSignatureVerifier.wrap(signatureVerifier),
        validateableAttestation,
        receivedOnSubnetId);
  }

  private static class AggregatorIndexAndEpoch {
    private final UInt64 aggregatorIndex;
    private final UInt64 epoch;

    private AggregatorIndexAndEpoch(final UInt64 aggregatorIndex, final UInt64 epoch) {
      this.aggregatorIndex = aggregatorIndex;
      this.epoch = epoch;
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final AggregatorIndexAndEpoch that = (AggregatorIndexAndEpoch) o;
      return Objects.equals(aggregatorIndex, that.aggregatorIndex)
          && Objects.equals(epoch, that.epoch);
    }

    @Override
    public int hashCode() {
      return Objects.hash(aggregatorIndex, epoch);
    }
  }
}
