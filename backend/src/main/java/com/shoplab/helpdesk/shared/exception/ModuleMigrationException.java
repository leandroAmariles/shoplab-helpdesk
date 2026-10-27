package com.shoplab.helpdesk.shared.exception;

/** The database was unreachable or a module's migrations failed while the application was starting. */
public class ModuleMigrationException extends TechnicalException {

    private final String module;

    public ModuleMigrationException(String module, ResponseCodeEnum responseCode, Throwable cause) {
        super(responseCode, "Schema of module '" + module + "': " + responseCode.getDefaultMessage(), cause);
        this.module = module;
    }

    public String getModule() {
        return module;
    }
}
