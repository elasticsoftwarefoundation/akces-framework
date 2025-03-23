package org.elasticsoftware.authapi;

import org.elasticsoftware.akces.query.QueryServiceApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Main application class for the Auth API query service.
 */
@SpringBootApplication
public class QueryServiceApplication extends org.elasticsoftware.akces.query.QueryServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(QueryServiceApplication.class, args);
    }
}