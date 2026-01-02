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

package org.elasticsoftware.akces.beans;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.ValueCodeGenerator;
import org.springframework.beans.factory.aot.AotServices;

import java.util.List;

public class AotServicesTest {
    @Test
    void testLoadAotServices() {
        AotServices.Loader loader = AotServices.factories();
        List<ValueCodeGenerator.Delegate> additionalDelegates = loader.load(ValueCodeGenerator.Delegate.class).asList();
        Assertions.assertFalse(additionalDelegates.isEmpty());
    }
}
