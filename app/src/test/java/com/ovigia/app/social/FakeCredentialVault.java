package com.ovigia.app.social;

import androidx.annotation.Nullable;

/** Cofre em memória, com as mesmas regras do de verdade (uma senha só, presa à conta). */
final class FakeCredentialVault implements CredentialVault {

    @Nullable String accountId;
    @Nullable String password;

    @Override
    public void save(String accountId, String password) {
        this.accountId = accountId;
        this.password = password;
    }

    @Nullable
    @Override
    public String read(String accountId) {
        if (this.accountId != null && !this.accountId.equals(accountId)) clear();
        return accountId.equals(this.accountId) ? password : null;
    }

    @Override
    public void clear() {
        accountId = null;
        password = null;
    }

    boolean isEmpty() {
        return password == null;
    }
}
