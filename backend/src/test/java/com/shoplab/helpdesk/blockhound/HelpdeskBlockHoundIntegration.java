package com.shoplab.helpdesk.blockhound;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

/**
 * Allowances of the blocking-call detector: each one names the exact class and method and says why it is accepted.
 * Never allow a whole package or a wildcard (specs/LAB-111, research R6).
 */
public class HelpdeskBlockHoundIntegration implements BlockHoundIntegration {

    @Override
    public void applyTo(BlockHound.Builder builder) {
        builder.allowBlockingCallsInside("org.springframework.util.MimeTypeUtils", "generateMultipartBoundary");
        // The model gateway client (openai-java) uses Kotlin reflection to read the request on its first call, and
        // kotlin-reflect then loads its built-in metadata from its own jar, once per JVM (LAB-84). It is a one-time read
        // of a classpath resource, not I/O that grows with load; without this the first chat request of a JVM fails
        // whenever no earlier test or request has warmed it up on another thread.
        builder.allowBlockingCallsInside(
                "kotlin.reflect.jvm.internal.impl.builtins.jvm.JvmBuiltInsPackageFragmentProvider", "findPackage");
    }
}
