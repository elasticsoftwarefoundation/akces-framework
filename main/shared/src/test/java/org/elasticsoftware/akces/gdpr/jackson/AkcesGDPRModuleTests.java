/*
 * Copyright 2022 - 2025 The Original Authors
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

package org.elasticsoftware.akces.gdpr.jackson;

import com.fasterxml.jackson.core.Version;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.semver4j.Semver;

public class AkcesGDPRModuleTests {
    @Test
    public void testVersion() {
        Version version = AkcesGDPRModule.generateVersion(Semver.parse("0.11.0-SNAPSHOT"));
        Assertions.assertNotEquals(Version.unknownVersion(), version);
        Assertions.assertEquals(new Version(0, 11, 0, "SNAPSHOT", "org.elasticsoftwarefoundation.akces", "akces-runtime"), version);
    }

    @Test
    public void testVersionFromManifest() {
        // in a test there is no manifest so this should be unknown
        AkcesGDPRModule akcesGDPRModule = new AkcesGDPRModule();
        Version version = akcesGDPRModule.version();
        Assertions.assertEquals(Version.unknownVersion(), version);
    }
}
