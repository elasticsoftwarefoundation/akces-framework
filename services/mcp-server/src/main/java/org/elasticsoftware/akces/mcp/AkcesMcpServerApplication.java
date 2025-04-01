package org.elasticsoftware.akces.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * MCP (Model Context Protocol) server application for Akces Framework.
 * Provides language services for Akces Framework components.
 */
@SpringBootApplication
public class AkcesMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AkcesMcpServerApplication.class, args);
    }
}