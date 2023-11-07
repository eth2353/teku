/*
 * Copyright Consensys Software Inc., 2023
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

package tech.pegasys.teku.validator.coordinator.publisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static tech.pegasys.teku.infrastructure.async.SafeFutureAssert.assertThatSafeFuture;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.pegasys.teku.infrastructure.async.SafeFuture;
import tech.pegasys.teku.spec.Spec;
import tech.pegasys.teku.spec.TestSpecFactory;
import tech.pegasys.teku.spec.datastructures.blocks.SignedBlockContainer;
import tech.pegasys.teku.spec.datastructures.validator.BroadcastValidationLevel;
import tech.pegasys.teku.spec.logic.common.statetransition.results.BlockImportResult;
import tech.pegasys.teku.spec.util.DataStructureUtil;
import tech.pegasys.teku.statetransition.block.BlockImportChannel;
import tech.pegasys.teku.statetransition.block.BlockImportChannel.BlockImportAndBroadcastValidationResults;
import tech.pegasys.teku.statetransition.validation.BlockValidator.BroadcastValidationResult;
import tech.pegasys.teku.validator.api.SendSignedBlockResult;
import tech.pegasys.teku.validator.coordinator.BlockFactory;
import tech.pegasys.teku.validator.coordinator.DutyMetrics;
import tech.pegasys.teku.validator.coordinator.performance.PerformanceTracker;

public class AbstractBlockPublisherTest {
  private final Spec spec = TestSpecFactory.createMinimalDeneb();
  private final DataStructureUtil dataStructureUtil = new DataStructureUtil(spec);
  private final BlockFactory blockFactory = mock(BlockFactory.class);
  private final BlockImportChannel blockImportChannel = mock(BlockImportChannel.class);
  private final PerformanceTracker performanceTracker = mock(PerformanceTracker.class);
  private final DutyMetrics dutyMetrics = mock(DutyMetrics.class);

  private final AbstractBlockPublisher blockPublisher =
      spy(
          new BlockPublisherTest(
              blockFactory, blockImportChannel, performanceTracker, dutyMetrics));

  final SignedBlockContainer signedBlockContents = dataStructureUtil.randomSignedBlockContents();

  @BeforeEach
  public void setUp() {
    when(blockFactory.unblindSignedBlockIfBlinded(signedBlockContents))
        .thenReturn(SafeFuture.completedFuture(signedBlockContents));
  }

  @Test
  public void
      sendSignedBlock_shouldPublishImmediatelyAndImportWhenBroadcastValidationIsNotRequired() {

    when(blockPublisher.importBlock(signedBlockContents, BroadcastValidationLevel.NOT_REQUIRED))
        .thenReturn(
            SafeFuture.completedFuture(
                new BlockImportAndBroadcastValidationResults(
                    SafeFuture.completedFuture(
                        BlockImportResult.successful(signedBlockContents.getSignedBlock())),
                    Optional.empty())));

    assertThatSafeFuture(
            blockPublisher.sendSignedBlock(
                signedBlockContents, BroadcastValidationLevel.NOT_REQUIRED))
        .isCompletedWithValue(SendSignedBlockResult.success(signedBlockContents.getRoot()));

    verify(blockPublisher).publishBlock(signedBlockContents);
    verify(blockPublisher).importBlock(signedBlockContents, BroadcastValidationLevel.NOT_REQUIRED);
  }

  @Test
  public void
      sendSignedBlock_shouldImportImmediatelyAndWaitToPublishWhenBroadcastValidationIsSpecified() {
    final Optional<SafeFuture<BroadcastValidationResult>> validationResult =
        Optional.of(new SafeFuture<>());
    when(blockPublisher.importBlock(
            signedBlockContents, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION))
        .thenReturn(
            SafeFuture.completedFuture(
                new BlockImportAndBroadcastValidationResults(
                    SafeFuture.completedFuture(
                        BlockImportResult.successful(signedBlockContents.getSignedBlock())),
                    validationResult)));

    assertThatSafeFuture(
            blockPublisher.sendSignedBlock(
                signedBlockContents, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION))
        .isCompletedWithValue(SendSignedBlockResult.success(signedBlockContents.getRoot()));

    verify(blockPublisher)
        .importBlock(signedBlockContents, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION);

    verify(blockPublisher, never()).publishBlock(signedBlockContents);

    validationResult.get().complete(BroadcastValidationResult.SUCCESS);

    verify(blockPublisher).publishBlock(signedBlockContents);
  }

  @Test
  public void sendSignedBlock_shouldImportImmediatelyAndNotPublishWhenBroadcastValidationFails() {
    final Optional<SafeFuture<BroadcastValidationResult>> validationResult =
        Optional.of(new SafeFuture<>());
    when(blockPublisher.importBlock(
            signedBlockContents, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION))
        .thenReturn(
            SafeFuture.completedFuture(
                new BlockImportAndBroadcastValidationResults(
                    SafeFuture.completedFuture(
                        BlockImportResult.successful(signedBlockContents.getSignedBlock())),
                    validationResult)));

    assertThatSafeFuture(
            blockPublisher.sendSignedBlock(
                signedBlockContents, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION))
        .isCompletedWithValue(SendSignedBlockResult.success(signedBlockContents.getRoot()));

    verify(blockPublisher)
        .importBlock(signedBlockContents, BroadcastValidationLevel.CONSENSUS_AND_EQUIVOCATION);

    verify(blockPublisher, never()).publishBlock(signedBlockContents);

    validationResult.get().complete(BroadcastValidationResult.CONSENSUS_FAILURE);

    verify(blockPublisher, never()).publishBlock(signedBlockContents);
  }

  private static class BlockPublisherTest extends AbstractBlockPublisher {
    public BlockPublisherTest(
        final BlockFactory blockFactory,
        final BlockImportChannel blockImportChannel,
        final PerformanceTracker performanceTracker,
        final DutyMetrics dutyMetrics) {
      super(blockFactory, blockImportChannel, performanceTracker, dutyMetrics);
    }

    @Override
    SafeFuture<BlockImportAndBroadcastValidationResults> importBlock(
        final SignedBlockContainer blockContainer,
        final BroadcastValidationLevel broadcastValidationLevel) {
      return null;
    }

    @Override
    void publishBlock(final SignedBlockContainer blockContainer) {}
  }
}
