package com.grimtorrenter.app;

/** currentPassword is only checked (and only needs to be supplied) once a password already
 * exists - see AuthResource.changePassword()'s own Javadoc for the bootstrap-vs-change
 * distinction. */
public record PasswordChangeRequest(String currentPassword, String newPassword) {
}
