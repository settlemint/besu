/*
 * Copyright contributors to Hyperledger Besu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.api.jsonrpc.internal.methods;

import org.hyperledger.besu.datatypes.AccessListEntry;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.ethereum.api.jsonrpc.RpcMethod;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.JsonRpcRequestContext;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.parameters.JsonCallParameter;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.response.RpcErrorType;
import org.hyperledger.besu.ethereum.api.jsonrpc.internal.results.CreateAccessListResult;
import org.hyperledger.besu.ethereum.api.query.BlockchainQueries;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.mainnet.ImmutableTransactionValidationParams;
import org.hyperledger.besu.ethereum.mainnet.TransactionValidationParams;
import org.hyperledger.besu.ethereum.transaction.CallParameter;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulator;
import org.hyperledger.besu.ethereum.transaction.TransactionSimulatorResult;
import org.hyperledger.besu.evm.tracing.AccessListOperationTracer;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public class EthCreateAccessList extends AbstractEstimateGas {

  public EthCreateAccessList(
      final BlockchainQueries blockchainQueries, final TransactionSimulator transactionSimulator) {
    super(blockchainQueries, transactionSimulator);
  }

  @Override
  public String getName() {
    return RpcMethod.ETH_CREATE_ACCESS_LIST.getMethodName();
  }

  @Override
  protected Object resultByBlockHeader(
      final JsonRpcRequestContext requestContext,
      final JsonCallParameter jsonCallParameter,
      final BlockHeader blockHeader) {
    final AccessListSimulatorResult maybeResult =
        processTransaction(jsonCallParameter, blockHeader);
    // if the call accessList is different from the simulation result, calculate gas and return
    if (shouldProcessWithAccessListOverride(jsonCallParameter, maybeResult.tracer())) {
      final AccessListSimulatorResult result =
          processTransactionWithAccessListOverride(
              jsonCallParameter, blockHeader, maybeResult.tracer().getAccessList());
      return createResponse(requestContext, result);
    } else {
      return createResponse(requestContext, maybeResult);
    }
  }

  private Object createResponse(
      final JsonRpcRequestContext requestContext, final AccessListSimulatorResult result) {
    return result
        .result()
        .map(createResponse(requestContext, result.tracer()))
        .orElseGet(() -> errorResponse(requestContext, RpcErrorType.INTERNAL_ERROR));
  }

  private TransactionValidationParams transactionValidationParams(
      final boolean isAllowExceedingBalance) {
    return ImmutableTransactionValidationParams.builder()
        .from(TransactionValidationParams.transactionSimulator())
        .isAllowExceedingBalance(isAllowExceedingBalance)
        .build();
  }

  private boolean shouldProcessWithAccessListOverride(
      final JsonCallParameter parameters, final AccessListOperationTracer tracer) {

    // if empty, transaction did not access any storage, does not need to reprocess
    if (tracer.getAccessList().isEmpty()) {
      return false;
    }

    // if empty, call did not include accessList, should reprocess
    if (parameters.getAccessList().isEmpty()) {
      return true;
    }

    // If call included access list, compare it with tracer result and return true if different
    return !Objects.equals(tracer.getAccessList(), parameters.getAccessList().get());
  }

  private Function<TransactionSimulatorResult, Object> createResponse(
      final JsonRpcRequestContext request, final AccessListOperationTracer operationTracer) {
    return result ->
        result.isSuccessful()
            ? new CreateAccessListResult(
                operationTracer.getAccessList(), processEstimateGas(result, operationTracer))
            : errorResponse(request, result);
  }

  private AccessListSimulatorResult processTransaction(
      final JsonCallParameter jsonCallParameter, final BlockHeader blockHeader) {
    final TransactionValidationParams transactionValidationParams =
        transactionValidationParams(!jsonCallParameter.isMaybeStrict().orElse(Boolean.FALSE));

    final CallParameter callParams =
        overrideGasLimitAndPrice(jsonCallParameter, blockHeader.getGasLimit());

    final AccessListOperationTracer tracer = AccessListOperationTracer.create();
    final Optional<TransactionSimulatorResult> result =
        transactionSimulator.process(callParams, transactionValidationParams, tracer, blockHeader);
    return new AccessListSimulatorResult(result, tracer);
  }

  private AccessListSimulatorResult processTransactionWithAccessListOverride(
      final JsonCallParameter jsonCallParameter,
      final BlockHeader blockHeader,
      final List<AccessListEntry> accessList) {
    final TransactionValidationParams transactionValidationParams =
        transactionValidationParams(!jsonCallParameter.isMaybeStrict().orElse(Boolean.FALSE));

    final AccessListOperationTracer tracer = AccessListOperationTracer.create();
    final CallParameter callParameter =
        overrideAccessList(jsonCallParameter, blockHeader.getGasLimit(), accessList);

    final Optional<TransactionSimulatorResult> result =
        transactionSimulator.process(
            callParameter, transactionValidationParams, tracer, blockHeader);
    return new AccessListSimulatorResult(result, tracer);
  }

  protected CallParameter overrideAccessList(
      final JsonCallParameter callParams,
      final long gasLimit,
      final List<AccessListEntry> accessListEntries) {
    return new CallParameter(
        callParams.getFrom(),
        callParams.getTo(),
        gasLimit,
        Optional.ofNullable(callParams.getGasPrice()).orElse(Wei.ZERO),
        callParams.getMaxPriorityFeePerGas(),
        callParams.getMaxFeePerGas(),
        callParams.getValue(),
        callParams.getPayload(),
        Optional.ofNullable(accessListEntries));
  }

  private record AccessListSimulatorResult(
      Optional<TransactionSimulatorResult> result, AccessListOperationTracer tracer) {}
}
