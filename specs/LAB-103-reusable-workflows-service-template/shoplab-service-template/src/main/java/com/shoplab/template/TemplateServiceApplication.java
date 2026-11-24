package com.shoplab.template;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class TemplateServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TemplateServiceApplication.class, args);
    }

    @PostConstruct
    public void init() {
        // Habilita la propagación automática de contexto de Reactor (traceparent, MDC, X-Request-Id)
        Hooks.enableAutomaticContextPropagation();
    }
}
