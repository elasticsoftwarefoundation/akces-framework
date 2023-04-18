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
