package com.truckhire.modules.user.entity;

/**
 * User account status enum.
 *
 * ACTIVE → Normal account, can login and use the platform
 * SUSPENDED → Admin has suspended this account (cannot login)
 * PENDING_VERIFICATION → Owner account awaiting KYC verification
 */
public enum UserStatus {
    ACTIVE,
    SUSPENDED,
    PENDING_VERIFICATION
}
