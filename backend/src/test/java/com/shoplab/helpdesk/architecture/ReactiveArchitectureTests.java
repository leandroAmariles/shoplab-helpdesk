package com.shoplab.helpdesk.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The reactive/blocking boundary holds in production code and every rule is able to fail (specs/LAB-111). */
class ReactiveArchitectureTests {

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.shoplab.helpdesk");

    private static final JavaClasses VIOLATIONS = new ClassFileImporter()
            .importPackages("com.shoplab.helpdesk.architecture.violations");

    static Stream<Supplier<ArchRule>> rules() {
        return Stream.of(
                ReactiveArchitectureRules::schedulersNotInDomainApplicationOrRest,
                ReactiveArchitectureRules::noJdbcInDomainOrApplication,
                ReactiveArchitectureRules::schedulersOnlyInInfrastructure,
                ReactiveArchitectureRules::noBlockingHttpClients,
                ReactiveArchitectureRules::noBlockingOnPublishers,
                ReactiveArchitectureRules::noSecureRandomIds);
    }

    @Test
    void productionCodeSatisfiesEveryRule() {
        rules().map(Supplier::get).forEach(rule -> rule.check(PRODUCTION));
    }

    @ParameterizedTest
    @MethodSource("rules")
    void everyRuleDetectsItsViolationFixture(Supplier<ArchRule> rule) {
        assertThatThrownBy(() -> rule.get().check(VIOLATIONS)).isInstanceOf(AssertionError.class);
    }
}
