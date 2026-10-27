package com.shoplab.helpdesk.architecture.violations.tools;

import org.springframework.ai.tool.annotation.Tool;

/** Fixture: an agent tool declared outside a module's api package (violates D4). */
public class ToolOutsideApi {

    @Tool(description = "Not published through an api package")
    public String lookup(String id) {
        return id;
    }
}
