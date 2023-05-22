package org.elasticsoftware.akcestest.util;

import org.elasticsoftware.akces.util.HostUtils;
import org.junit.jupiter.api.Test;

public class HostUtilsTests {
    @Test
    public void testGetHostName() {
        String hostName = HostUtils.getHostName();
        System.out.println(hostName);
    }
}
