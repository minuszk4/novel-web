package com.novelweb.library.publication;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.ObjectMapper;

@Configuration
@Profile("postgres")
class PublicationTakedownPurgeConfiguration {

  @Bean
  PublicationPurgeTargetRegistry publicationPurgeTargetRegistry(
      PublicationPurgeProperties properties) {
    return PublicationPurgeTargetRegistry.from(properties);
  }

  @Bean
  PublicationTakedownPurgeTransport publicationTakedownPurgeTransport(ObjectMapper objectMapper) {
    return PublicationTakedownPurgeTransport.jdk(objectMapper);
  }
}
