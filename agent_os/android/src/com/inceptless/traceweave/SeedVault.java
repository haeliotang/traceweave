// SPDX-FileCopyrightText: 2026 Inceptless <support@inceptless.com>
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.inceptless.traceweave;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Wraps the sync identity seed with a non-exportable Keystore key.
 *
 * <p>The seed used to be a plain hex string in SharedPreferences, so copying the app's data
 * (root, forensic image, a restored backup) was enough to impersonate this device to a paired
 * peer. The Keystore key cannot leave the device, so a copied preference file is useless
 * without it.
 */
final class SeedVault {
    private static final String KEY_ALIAS = "com.inceptless.traceweave.syncseed.v1";
    private static final String PREF_WRAPPED = "sync_seed_wrapped";
    private static final String PREF_LEGACY = "sync_seed";
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private SeedVault() {}

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build());
        return generator.generateKey();
    }

    /** Returns the wrapped seed, or null when no seed has been created yet. */
    static String read(Context context) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("agent_os_prefs", 0);
        String wrapped = prefs.getString(PREF_WRAPPED, "");
        if (wrapped != null && !wrapped.isEmpty()) return unwrap(wrapped);
        String legacy = prefs.getString(PREF_LEGACY, "");
        if (legacy == null || legacy.isEmpty()) return null;
        // One-time migration: an install from before the vault keeps its identity.
        write(context, legacy);
        prefs.edit().remove(PREF_LEGACY).commit();
        return legacy;
    }

    static void write(Context context, String seed) throws Exception {
        if (seed == null || seed.isEmpty()) throw new IllegalArgumentException("Seed is empty");
        SharedPreferences prefs = context.getSharedPreferences("agent_os_prefs", 0);
        prefs.edit().putString(PREF_WRAPPED, wrap(seed)).remove(PREF_LEGACY).commit();
    }

    private static String wrap(String seed) throws Exception {
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[IV_BYTES + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, IV_BYTES);
        System.arraycopy(encrypted, 0, combined, IV_BYTES, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private static String unwrap(String wrapped) throws Exception {
        byte[] combined = Base64.decode(wrapped, Base64.NO_WRAP);
        if (combined.length <= IV_BYTES) throw new IllegalStateException("Wrapped seed is truncated");
        Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, combined, 0, IV_BYTES));
        byte[] plain = cipher.doFinal(combined, IV_BYTES, combined.length - IV_BYTES);
        return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
    }
}
