package com.shoplab.helpdesk.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Rules that make the layers and module boundaries executable (specs/LAB-105, contracts/architecture-rules.md).
 * Each module has {@code api}, {@code domain}, {@code application}, {@code infrastructure} and {@code config};
 * {@code shared} and the application class are outside these rules.
 */
final class ModuleArchitectureRules {

    /** Families the domain must not know: AI, messaging, persistence mapping and reactive types. */
    static final List<String> FORBIDDEN_IN_DOMAIN = List.of(
            "org.springframework.ai..",
            "org.springframework.kafka..", "org.apache.kafka..",
            "jakarta.persistence..", "javax.persistence..", "org.springframework.data.jpa..", "org.hibernate..",
            "reactor..");

    private static final String PROJECT = "com.shoplab.helpdesk";

    private ModuleArchitectureRules() {
    }

    /** D1: the domain does not import Spring AI, Kafka, JPA or Reactor ({@code Mono}/{@code Flux} live outside it). */
    static ArchRule domainIsFreeOfFrameworks() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_IN_DOMAIN.toArray(String[]::new))
                .because("the domain is plain Java; frameworks and reactive types belong to application and "
                        + "infrastructure")
                .allowEmptyShould(true);
    }

    /** D2: the domain depends on no outer layer of the project. */
    static ArchRule domainDependsOnNoOuterLayer() {
        return noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        PROJECT + "..application..", PROJECT + "..infrastructure..",
                        PROJECT + "..api..", PROJECT + "..config..")
                .because("dependencies point inwards (hexagonal architecture)")
                .allowEmptyShould(true);
    }

    /** D2: use cases talk to ports, never to adapters. */
    static ArchRule applicationDoesNotUseInfrastructure() {
        return noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAPackage(PROJECT + "..infrastructure..")
                .because("use cases depend on ports, not on adapters")
                .allowEmptyShould(true);
    }

    /** D3: everything a business module contains sits in one of its layers. */
    static ArchRule classesLiveInALayer(List<String> modulePackages) {
        CompositeArchRule rules = null;
        for (String module : modulePackages) {
            ArchRule rule = classes().that().resideInAPackage(module + "..")
                    .and().haveSimpleNameNotEndingWith("package-info")
                    .should().resideInAnyPackage(module + ".api..", module + ".domain..", module + ".application..",
                            module + ".infrastructure..", module + ".config..")
                    .because("a module is split in api, domain, application, infrastructure and config")
                    .allowEmptyShould(true);
            rules = rules == null ? CompositeArchRule.of(rule) : rules.and(rule);
        }
        return rules;
    }

    /** D4: agent tools are published through a module's {@code api} package, nowhere else. */
    static ArchRule toolsOnlyInApi() {
        return methods().that().areAnnotatedWith(Tool.class)
                .should().beDeclaredInClassesThat().resideInAPackage("..api..")
                .because("each module publishes its tools in its api; chat only composes them")
                .allowEmptyShould(true);
    }

    /** D5: a module reaches another one only through its {@code api}. */
    static ArchRule modulesUseOnlyTheApiOfOthers(List<String> modulePackages) {
        CompositeArchRule rules = null;
        for (String module : modulePackages) {
            for (String other : modulePackages) {
                if (module.equals(other)) {
                    continue;
                }
                ArchRule rule = noClasses().that().resideInAPackage(module + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(other + ".domain..",
                                other + ".application..", other + ".infrastructure..", other + ".config..")
                        .because(module + " must use the api of " + other + ", not its internals")
                        .allowEmptyShould(true);
                rules = rules == null ? CompositeArchRule.of(rule) : rules.and(rule);
            }
        }
        return rules;
    }
}
