package br.com.greenv.videoapi.config;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdentifierConfiguration {

    @Bean
    RandomGenerator secureIdentifierRandomGenerator() {
        return new SecureRandom();
    }
}
