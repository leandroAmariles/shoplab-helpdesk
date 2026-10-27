package com.shoplab.helpdesk.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.shoplab.helpdesk.architecture.violations.domain.ReactorInDomain;
import com.shoplab.helpdesk.architecture.violations.domain.SpringAiInDomain;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Layers and module boundaries hold in production code and every rule is able to fail (specs/LAB-105,
 * contracts/architecture-rules.md). Same pattern as the reactive rules of LAB-111.
 */
class ModuleArchitectureTests {

    private static final String ROOT = "com.shoplab.helpdesk";
    private static final String FIXTURES = ROOT + ".architecture.violations";
    private static final List<String> BUSINESS_MODULES = List.of(
            ROOT + ".chat", ROOT + ".retrieval", ROOT + ".orders", ROOT + ".catalog", ROOT + ".tickets");

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    private static final JavaClasses VIOLATIONS = new ClassFileImporter().importPackages(FIXTURES);

    /** A rule applied to production code and to the fixture that must break it. */
    record RuleCase(String name, Supplier<ArchRule> production, Supplier<ArchRule> violation) {

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<RuleCase> rules() {
        List<String> fakeModule = List.of(FIXTURES + ".layers.fakemodule");
        List<String> twoFakeModules = List.of(FIXTURES + ".modules.alpha", FIXTURES + ".modules.beta");
        return Stream.of(
                new RuleCase("D1 domain is free of frameworks",
                        ModuleArchitectureRules::domainIsFreeOfFrameworks,
                        ModuleArchitectureRules::domainIsFreeOfFrameworks),
                new RuleCase("D2 domain depends on no outer layer",
                        ModuleArchitectureRules::domainDependsOnNoOuterLayer,
                        ModuleArchitectureRules::domainDependsOnNoOuterLayer),
                new RuleCase("D2 application does not use infrastructure",
                        ModuleArchitectureRules::applicationDoesNotUseInfrastructure,
                        ModuleArchitectureRules::applicationDoesNotUseInfrastructure),
                new RuleCase("D3 classes live in a layer",
                        () -> ModuleArchitectureRules.classesLiveInALayer(BUSINESS_MODULES),
                        () -> ModuleArchitectureRules.classesLiveInALayer(fakeModule)),
                new RuleCase("D4 tools only in api",
                        ModuleArchitectureRules::toolsOnlyInApi,
                        ModuleArchitectureRules::toolsOnlyInApi),
                new RuleCase("D5 modules use only the api of others",
                        () -> ModuleArchitectureRules.modulesUseOnlyTheApiOfOthers(BUSINESS_MODULES),
                        () -> ModuleArchitectureRules.modulesUseOnlyTheApiOfOthers(twoFakeModules)));
    }

    @Test
    void productionCodeSatisfiesEveryRule() {
        rules().forEach(rule -> rule.production().get().check(PRODUCTION));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void everyRuleDetectsItsViolationFixture(RuleCase rule) {
        assertThatThrownBy(() -> rule.violation().get().check(VIOLATIONS)).isInstanceOf(AssertionError.class);
    }

    @Test
    void theDomainRuleRejectsSpringAiAndReactorAndNamesTheClass() {
        ArchRule rule = ModuleArchitectureRules.domainIsFreeOfFrameworks();

        assertThatThrownBy(() -> rule.check(new ClassFileImporter().importClasses(SpringAiInDomain.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("SpringAiInDomain")
                .hasMessageContaining("org.springframework.ai");
        assertThatThrownBy(() -> rule.check(new ClassFileImporter().importClasses(ReactorInDomain.class)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("ReactorInDomain")
                .hasMessageContaining("reactor");
    }

    @Test
    void theDomainRuleAlsoForbidsKafkaAndJpa() {
        assertThat(ModuleArchitectureRules.FORBIDDEN_IN_DOMAIN).contains(
                "org.springframework.kafka..", "org.apache.kafka..",
                "jakarta.persistence..", "org.springframework.data.jpa..");
    }
}
