/*
 * Copyright 2019 ConsenSys AG.
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

package tech.pegasys.teku.spec.logic.common.util;

import static com.google.common.base.Preconditions.checkArgument;

import org.apache.tuweni.bytes.Bytes32;
import tech.pegasys.teku.bls.BLSSignature;
import tech.pegasys.teku.infrastructure.unsigned.UInt64;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlock;
import tech.pegasys.teku.spec.datastructures.blocks.BeaconBlockAndState;
import tech.pegasys.teku.spec.datastructures.blocks.Eth1Data;
import tech.pegasys.teku.spec.datastructures.blocks.blockbody.BeaconBlockBody;
import tech.pegasys.teku.spec.datastructures.operations.Attestation;
import tech.pegasys.teku.spec.datastructures.operations.AttesterSlashing;
import tech.pegasys.teku.spec.datastructures.operations.Deposit;
import tech.pegasys.teku.spec.datastructures.operations.ProposerSlashing;
import tech.pegasys.teku.spec.datastructures.operations.SignedVoluntaryExit;
import tech.pegasys.teku.spec.datastructures.state.beaconstate.BeaconState;
import tech.pegasys.teku.spec.logic.common.statetransition.StateTransition;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.BlockProcessingException;
import tech.pegasys.teku.spec.logic.common.statetransition.exceptions.StateTransitionException;
import tech.pegasys.teku.spec.schemas.SchemaDefinitions;
import tech.pegasys.teku.ssz.SszList;

public class BlockProposalUtil {

  private final StateTransition stateTransition;
  private final SchemaDefinitions schemaDefinitions;

  public BlockProposalUtil(
      final SchemaDefinitions schemaDefinitions, final StateTransition stateTransition) {
    this.schemaDefinitions = schemaDefinitions;
    this.stateTransition = stateTransition;
  }

  public BeaconBlockAndState createNewUnsignedBlock(
      final UInt64 newSlot,
      final int proposerIndex,
      final BLSSignature randaoReveal,
      final BeaconState blockSlotState,
      final Bytes32 parentBlockSigningRoot,
      final Eth1Data eth1Data,
      final Bytes32 graffiti,
      final SszList<Attestation> attestations,
      final SszList<ProposerSlashing> proposerSlashings,
      final SszList<AttesterSlashing> attesterSlashings,
      final SszList<Deposit> deposits,
      final SszList<SignedVoluntaryExit> voluntaryExits)
      throws StateTransitionException {
    checkArgument(
        blockSlotState.getSlot().equals(newSlot),
        "Block slot state from incorrect slot. Expected %s but got %s",
        newSlot,
        blockSlotState.getSlot());

    // Create block body
    final BeaconBlockBody beaconBlockBody =
        schemaDefinitions
            .getBeaconBlockBodySchema()
            .createBlockBody(
                randaoReveal,
                eth1Data,
                graffiti,
                proposerSlashings,
                attesterSlashings,
                attestations,
                deposits,
                voluntaryExits);

    // Create initial block with some stubs
    final Bytes32 tmpStateRoot = Bytes32.ZERO;
    BeaconBlock newBlock =
        schemaDefinitions
            .getBeaconBlockSchema()
            .create(
                newSlot,
                UInt64.valueOf(proposerIndex),
                parentBlockSigningRoot,
                tmpStateRoot,
                beaconBlockBody);

    // Run state transition and set state root
    try {
      final BeaconState newState = stateTransition.processBlock(blockSlotState, newBlock);

      Bytes32 stateRoot = newState.hashTreeRoot();
      BeaconBlock newCompleteBlock = newBlock.withStateRoot(stateRoot);

      return new BeaconBlockAndState(newCompleteBlock, newState);
    } catch (final BlockProcessingException e) {
      throw new StateTransitionException(e);
    }
  }
}
