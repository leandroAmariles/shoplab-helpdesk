package com.shoplab.helpdesk.eval.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Wiring of the eval module. */
@Configuration
@EnableConfigurationProperties(EvalProperties.class)
public class EvalConfig {
}
