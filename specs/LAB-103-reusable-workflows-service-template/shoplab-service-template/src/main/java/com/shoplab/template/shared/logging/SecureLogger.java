package com.shoplab.template.shared.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;

/**
 * Wrapper de logging seguro que enmascara información sensible (tarjetas, tokens, contraseñas)
 * antes de emitir a SLF4J / Logback.
 */
public class SecureLogger {

    private final Logger delegate;

    private static final Pattern JWT_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.?[A-Za-z0-9-_.+/=]*");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern SECRET_PATTERN = Pattern.compile("(?i)(password|secret|token|apiKey)\\s*[:=]\\s*['\"]?([^'\"\\s]+)['\"]?");

    public SecureLogger(Class<?> clazz) {
        this.delegate = LoggerFactory.getLogger(clazz);
    }

    public void info(String format, Object... arguments) {
        if (delegate.isInfoEnabled()) {
            delegate.info(mask(format), maskArgs(arguments));
        }
    }

    public void warn(String format, Object... arguments) {
        if (delegate.isWarnEnabled()) {
            delegate.warn(mask(format), maskArgs(arguments));
        }
    }

    public void error(String format, Object... arguments) {
        if (delegate.isErrorEnabled()) {
            delegate.error(mask(format), maskArgs(arguments));
        }
    }

    public void error(String message, Throwable throwable) {
        if (delegate.isErrorEnabled()) {
            delegate.error(mask(message), throwable);
        }
    }

    public void debug(String format, Object... arguments) {
        if (delegate.isDebugEnabled()) {
            delegate.debug(mask(format), maskArgs(arguments));
        }
    }

    private String mask(String message) {
        if (message == null) return null;
        String result = JWT_PATTERN.matcher(message).replaceAll("Bearer [MASKED_TOKEN]");
        result = CARD_PATTERN.matcher(result).replaceAll("[MASKED_CARD]");
        result = SECRET_PATTERN.matcher(result).replaceAll("$1=[MASKED_SECRET]");
        return result;
    }

    private Object[] maskArgs(Object... arguments) {
        if (arguments == null) return new Object[0];
        Object[] masked = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            if (arguments[i] instanceof String s) {
                masked[i] = mask(s);
            } else {
                masked[i] = arguments[i];
            }
        }
        return masked;
    }
}
