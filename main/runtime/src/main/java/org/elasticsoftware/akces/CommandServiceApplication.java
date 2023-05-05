package org.elasticsoftware.akces;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication(exclude = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(KafkaProperties.class)
public class CommandServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CommandServiceApplication.class, args);
    }
}
