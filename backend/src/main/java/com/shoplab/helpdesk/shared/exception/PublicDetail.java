package com.shoplab.helpdesk.shared.exception;

/**
 * An exception that may show a safe detail in the error body, next to the fixed message of its code (for example the
 * field of a request that broke a rule). The detail never carries free text of customers or answers; a {@code null}
 * detail is left out of the body.
 */
public interface PublicDetail {

    String publicDetail();
}
