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

/**
 * A {@link RequestingParty} implementation representing a human user that requested
 * a task assignment.
 *
 * <p>For GDPR reasons, this record does not carry any personally identifiable
 * information beyond a system-level user identifier and role.
 *
 * @param userId the unique identifier of the human user
 * @param role   the role of the human user in the system
 *               (e.g. "administrator", "analyst", "end-user")
 */
public record HumanRequestingParty(
        String userId,
        String role
) implements RequestingParty {
}
