package com.shoplab.helpdesk.architecture;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import java.net.HttpURLConnection;
import java.security.SecureRandom;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Rules that make the reactive/blocking boundary executable (specs/LAB-111, contracts/blocking-isolation.md). */
final class ReactiveArchitectureRules {

    private ReactiveArchitectureRules() {
    }

    /** A1: nothing but infrastructure knows about schedulers; the web edge does not either. */
    static ArchRule schedulersNotInDomainApplicationOrRest() {
        return noClasses().that().resideInAnyPackage("..domain..", "..application..", "..in.rest..")
                .should().dependOnClassesThat().belongToAnyOf(Schedulers.class)
                .because("only infrastructure isolates blocking calls");
    }

    /** A2: the domain and the use cases do not touch JDBC. */
    static ArchRule noJdbcInDomainOrApplication() {
        return noClasses().that().resideInAnyPackage("..domain..", "..application..")
                .should().dependOnClassesThat().resideInAnyPackage("java.sql..", "javax.sql..",
                        "org.springframework.jdbc..")
                .because("persistence is an infrastructure concern behind a port");
    }

    /** A3: {@code Schedulers} is only used from infrastructure. */
    static ArchRule schedulersOnlyInInfrastructure() {
        return noClasses().that().resideOutsideOfPackage("..infrastructure..")
                .should().dependOnClassesThat().belongToAnyOf(Schedulers.class);
    }

    /** A4: outgoing HTTP goes through the reactive client. */
    static ArchRule noBlockingHttpClients() {
        return noClasses().should().dependOnClassesThat()
                .belongToAnyOf(RestTemplate.class, RestClient.class, HttpURLConnection.class,
                        java.net.http.HttpClient.class)
                .because("outgoing HTTP calls use WebClient");
    }

    /** A5: nobody waits for a publisher. */
    static ArchRule noBlockingOnPublishers() {
        return noClasses().should().callMethodWhere(
                        target(nameMatching("block.*|toIterable|toStream"))
                                .and(target(owner(assignableTo(Mono.class)))
                                        .or(target(owner(assignableTo(Flux.class))))))
                .because("a blocking wait on a publisher defeats the reactive chain");
    }

    /** A6: random ids must not come from the OS entropy source, which blocks on Linux. */
    static ArchRule noSecureRandomIds() {
        return noClasses().should().callMethod(UUID.class, "randomUUID")
                .orShould().dependOnClassesThat().belongToAnyOf(SecureRandom.class)
                .because("UUID.randomUUID() and SecureRandom read /dev/urandom on the event loop");
    }
}
