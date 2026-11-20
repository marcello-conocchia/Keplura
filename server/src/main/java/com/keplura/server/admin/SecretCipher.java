package com.keplura.server.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for provider API keys (ADMIN-KI Scope B,
 * docs/adr/0008 §4). The master key comes ONLY from the ENV
 * {@code KEPLURA_SECRETS_MASTER_KEY} (.env stays the secret boundary — the
 * PO's GRENZEN rule); it never enters the database, a response, or a log.
 *
 * <p>Key derivation: HKDF-SHA256 (RFC 5869, extract+expand) over the raw
 * ENV value with a fixed domain-separation salt/info pair — NOT a bare
 * SHA-256 of it. Rationale (reviewer note on the ADR): a human-chosen master
 * key is not a uniform 256-bit key; HKDF concentrates whatever entropy
 * exists, binds the derived key to this exact purpose ("ai-provider-secret"),
 * and stays the standard construct auditors expect for key material derived
 * from a shared secret.
 *
 * <p>Envelope: random 12-byte IV per call (GCM's nonce requirement — two
 * ciphertexts under one key must never reuse it), stored as
 * Base64(IV || ciphertext+tag). Authentication is built in: a flipped bit
 * makes decrypt throw instead of returning garbage.
 *
 * <p>No master key set = feature degraded honestly, NOT fatally (ADR-0008 §8
 * startup rule): {@link #available()} is false, writing a secret answers 503
 * MASTER_KEY_MISSING, and the rest of the admin surface (and the whole app)
 * keeps working — a deployment that stores no provider keys (e.g. pure local
 * Ollama) never needs one.
 */
@Service
public class SecretCipher {

    /** What any read of a secret field may ever show (PO: "nur maskiert anzeigen"). */
    public static final String MASK = "****";

    private static final Logger log = LoggerFactory.getLogger(SecretCipher.class);
    private static final byte[] HKDF_SALT = "intracore:secret-cipher:v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO = "ai-provider-secret".getBytes(StandardCharsets.UTF_8);
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec derivedKey; // null = master key not configured
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${keplura.secrets.master-key:}") String masterKey) {
        if (masterKey == null || masterKey.isBlank()) {
            this.derivedKey = null;
            log.info("secret cipher: no KEPLURA_SECRETS_MASTER_KEY — provider secrets cannot be stored "
                    + "(admin surface stays usable without them)");
        } else {
            this.derivedKey = new SecretKeySpec(
                    hkdfSha256(masterKey.getBytes(StandardCharsets.UTF_8)), "AES");
            log.info("secret cipher: master key loaded (AES-256-GCM, HKDF-derived)");
        }
    }

    public boolean available() {
        return derivedKey != null;
    }

    /** Encrypt a plaintext secret; refuses with MASTER_KEY_MISSING when no key is set. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            throw new IllegalArgumentException("nothing to encrypt");
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, derivedKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, envelope, 0, iv.length);
            System.arraycopy(sealed, 0, envelope, iv.length, sealed.length);
            return Base64.getEncoder().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            // never an actionable diagnostic for the caller; the class name stays in the log
            log.error("secret encryption failed ({})", e.getClass().getSimpleName());
            throw new IllegalStateException("secret encryption unavailable");
        }
    }

    /** Decrypt a stored envelope. Never returns partial garbage: GCM auth or throw. */
    public String decrypt(String envelopeBase64) {
        requireKey();
        try {
            byte[] envelope = Base64.getDecoder().decode(envelopeBase64);
            if (envelope.length <= IV_BYTES) {
                throw new IllegalArgumentException("envelope too short");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, derivedKey, new GCMParameterSpec(TAG_BITS, envelope, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(envelope, IV_BYTES, envelope.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // corrupt ciphertext or a foreign master key (rotation case): honest failure
            throw new IllegalStateException("stored secret could not be decrypted "
                    + "(master key changed or ciphertext corrupt)");
        }
    }

    private void requireKey() {
        if (derivedKey == null) {
            throw AdminException.masterKeyMissing();
        }
    }

    /**
     * HKDF-SHA256 (RFC 5869) with empty IKM-independent salt pair fixed per
     * class constants. 32-byte output = one AES-256 key. Implemented with the
     * JDK Mac directly — the project deliberately adds no new crypto library.
     */
    static byte[] hkdfSha256(byte[] inputKeyMaterial) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HKDF_SALT, "HmacSHA256"));
            byte[] prk = mac.doFinal(inputKeyMaterial);           // extract
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));       // expand (T1 only, 32 bytes needed)
            byte[] info = HKDF_INFO;
            byte[] t1 = mac.doFinal(concat(info, new byte[]{1}));
            return t1;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HKDF unavailable", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
