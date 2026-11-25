package com.shoplab.template.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class HexagonalArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.shoplab.template");
    }

    @Test
    @DisplayName("Regla 1: La capa domain no debe depender de application, infrastructure ni frameworks")
    void domain_should_not_depend_on_outer_layers_or_frameworks() {
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..application..",
                        "..infrastructure..",
                        "org.springframework..",
                        "reactor.core..",
                        "jakarta.persistence.."
                )
                .because("Domain must remain pure business logic isolated from infrastructure and delivery mechanisms")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("Regla 2: El servicio no debe depender de clases del monolito shoplab-helpdesk")
    void service_must_not_import_monolith_classes() {
        noClasses().should().dependOnClassesThat()
                .resideInAPackage("com.shoplab.helpdesk..")
                .because("A standalone service must never import code from the monolith (ADR-0015, ADR-0031)")
                .allowEmptyShould(true)
                .check(importedClasses);
    }

    @Test
    @DisplayName("Regla 3: Las capas application y usecase no deben construir ResponseEntity directamente")
    void application_layer_must_not_use_http_entities() {
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().belongToAnyOf(ResponseEntity.class)
                .because("HTTP transport constructs belong exclusively to the REST adapter layer")
                .allowEmptyShould(true)
                .check(importedClasses);
    }
}
