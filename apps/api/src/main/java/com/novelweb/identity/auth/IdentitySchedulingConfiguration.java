package com.novelweb.identity.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@Profile("postgres")
@EnableScheduling
class IdentitySchedulingConfiguration {
}
