package org.elasticsoftware.authapi;

import org.elasticsoftware.akces.client.CommandServiceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for the Auth API command service.
 */
@SpringBootApplication
public class CommandServiceApplication extends org.elasticsoftware.akces.client.CommandServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(CommandServiceApplication.class, args);
    }
}