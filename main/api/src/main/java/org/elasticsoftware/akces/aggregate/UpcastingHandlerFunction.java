/*
 * Copyright 2022 - 2026 The Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 */

package org.elasticsoftware.akces.aggregate;

@FunctionalInterface
public interface UpcastingHandlerFunction<T , R , TR extends ProtocolRecordType<T>, RR extends ProtocolRecordType<R>> {
    R apply(T t);

    default TR getInputType() {
        throw new UnsupportedOperationException("When implementing UpcastingHandlerFunction directly, you must override getInputType()");
    }

    default RR getOutputType() {
        throw new UnsupportedOperationException("When implementing UpcastingHandlerFunction directly, you must override getOutputType()");
    }

    default Aggregate<? extends AggregateState> getAggregate() {
        throw new UnsupportedOperationException("When implementing UpcastingHandlerFunction directly, you must override getAggregate()");
    }
}
