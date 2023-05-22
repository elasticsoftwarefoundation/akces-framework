/*
 * Copyright 2022 - 2023 The Original Authors
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

package org.elasticsoftware.akces.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class HostUtils {
    private HostUtils() {
        // Utility class
    }

    public static String getHostName() {
        // obtain a hostname. First try to get the host name from docker container (from the "HOSTNAME" environment variable)
        String hostName = System.getenv("HOSTNAME");
        // if the application is not running in a docker container, we can to obtain the hostname using the "java.net.InetAddress" class
        if(hostName == null || hostName.isEmpty()) {
            try {
                InetAddress addr = InetAddress.getLocalHost();
                hostName = addr.getHostName();
            } catch (Exception e) {
                hostName = "Unknown";
            }
        }
        return hostName;
    }
}
