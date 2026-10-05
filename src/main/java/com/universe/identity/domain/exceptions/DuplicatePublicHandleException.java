package com.universe.identity.domain.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class DuplicatePublicHandleException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public DuplicatePublicHandleException(String handle) {
        super("IDENTITY_PUBLIC_HANDLE_EXISTS", "Public handle đã tồn tại: " + handle);
    }

    public DuplicatePublicHandleException(String handle, Throwable cause) {
        super("IDENTITY_PUBLIC_HANDLE_EXISTS", "Public handle đã tồn tại: " + handle);
        initCause(cause);
    }
}
