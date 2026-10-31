package com.fastwork.expense.passkey;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

/**
 * Authenticator WebAuthn palsu untuk uji integrasi (pola Yubico TestAuthenticator,
 * tapi Java + encoder CBOR kecil di sini). ES256 self-attestation fmt "none" —
 * cukup untuk flow server yang tidak memeriksa trust chain. Counter mulai 0
 * (legal untuk authenticator platform); setiap assertion sukses menaikkan 1.
 */
final class FakeAuthenticator {

    private static final int UP = 0x01;
    private static final int UV = 0x04;
    private static final int BS = 0x10;
    private static final int AT = 0x40; // attested credential data hadir

    private final KeyPair keyPair;
    private final byte[] credentialId;
    private final byte[] userHandle;
    private final String rpId;
    private final String origin;
    private long counter = 0;

    private FakeAuthenticator(
            byte[] credentialId, byte[] userHandle, String rpId, String origin) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            this.keyPair = generator.generateKeyPair();
            this.credentialId = credentialId;
            this.userHandle = userHandle;
            this.rpId = rpId;
            this.origin = origin;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** ID kredensial deterministik dari seed agar dua authenticator mudah dibedakan. */
    static FakeAuthenticator create(int seed, byte[] userHandle, String rpId, String origin) {
        byte[] credentialId = new byte[32];
        for (int i = 0; i < credentialId.length; i++) {
            credentialId[i] = (byte) (seed * 31 + i * 7 + 3);
        }
        return new FakeAuthenticator(credentialId, userHandle, rpId, origin);
    }

    // ---------- Registrasi ----------

    /** JSON PublicKeyCredential lengkap hasil navigator.credentials.create (format webauthn-json). */
    String registrationResponseJson(String challengeB64Url) {
        byte[] clientData = clientDataJson("webauthn.create", challengeB64Url);
        byte[] authData = authenticatorData(UP | UV | AT, 0, true);
        byte[] attestation = attestationObject(authData);
        String id = b64url(credentialId);
        return "{\"id\":\""
                + id
                + "\",\"rawId\":\""
                + id
                + "\",\"type\":\"public-key\",\"clientExtensionResults\":{},\"response\":"
                + "{\"clientDataJSON\":\""
                + b64url(clientData)
                + "\",\"attestationObject\":\""
                + b64url(attestation)
                + "\",\"transports\":[\"internal\"]}}";
    }

    // ---------- Assertion ----------

    /** JSON PublicKeyCredential hasil navigator.credentials.get untuk challenge login. */
    String assertionResponseJson(String challengeB64Url, boolean backupState) throws Exception {
        byte[] clientData = clientDataJson("webauthn.get", challengeB64Url);
        int flags = UP | UV;
        if (backupState) {
            flags |= BS;
        }
        long count = ++counter;
        return assertionJson(challengeB64Url, clientData, flags, count);
    }

    /** Assertion dengan counter beku di angka terakhir yang diterima server (uji anti-replay). */
    String frozenCounterAssertion(String challengeB64Url) throws Exception {
        byte[] clientData = clientDataJson("webauthn.get", challengeB64Url);
        return assertionJson(challengeB64Url, clientData, UP | UV, counter);
    }

    private String assertionJson(
            String challengeB64Url, byte[] clientData, int flags, long count) throws Exception {
        byte[] authData = authenticatorData(flags, count, false);
        byte[] signature = sign(concat(authData, sha256(clientData)));
        String id = b64url(credentialId);
        return "{\"id\":\""
                + id
                + "\",\"rawId\":\""
                + id
                + "\",\"type\":\"public-key\",\"clientExtensionResults\":{},\"response\":"
                + "{\"clientDataJSON\":\""
                + b64url(clientData)
                + "\",\"authenticatorData\":\""
                + b64url(authData)
                + "\",\"signature\":\""
                + b64url(signature)
                + "\",\"userHandle\":\""
                + b64url(userHandle)
                + "\"}}";
    }

    String credentialIdB64Url() {
        return b64url(credentialId);
    }

    // ---------- internal ----------

    private byte[] authenticatorData(int flags, long count, boolean includeKeys) {
        byte[] rpIdHash = sha256(rpId.getBytes(StandardCharsets.UTF_8));
        byte[] attested = includeKeys ? attestedCredentialData() : new byte[0];
        byte[] data = new byte[32 + 1 + 4 + attested.length];
        int offset = 0;
        System.arraycopy(rpIdHash, 0, data, offset, 32);
        offset += 32;
        data[offset++] = (byte) flags;
        for (int i = 3; i >= 0; i--) {
            data[offset + i] = (byte) (count & 0xFF);
            count >>= 8;
        }
        offset += 4;
        if (attested.length > 0) {
            System.arraycopy(attested, 0, data, offset, attested.length);
        }
        return data;
    }

    /** attested credential data: aaguid(16) + panjang id(2) + credential id + kunci publik COSE. */
    private byte[] attestedCredentialData() {
        byte[] cose = coseECP256Key();
        byte[] out = new byte[18 + credentialId.length + cose.length];
        for (int i = 0; i < 16; i++) {
            out[i] = (byte) (0x10 + i);
        }
        int len = credentialId.length;
        out[16] = (byte) (len >> 8);
        out[17] = (byte) (len & 0xFF);
        System.arraycopy(credentialId, 0, out, 18, len);
        System.arraycopy(cose, 0, out, 18 + len, cose.length);
        return out;
    }

    /** Kunci publik COSE ES256 (P-256): map {1:2, 3:-7, -1:1, -2:x, -3:y}. */
    private byte[] coseECP256Key() {
        ECPublicKey publicKey = (ECPublicKey) keyPair.getPublic();
        byte[] x = to32(publicKey.getW().getAffineX());
        byte[] y = to32(publicKey.getW().getAffineY());
        byte[] result = new byte[77];
        result[0] = (byte) 0xA5; // map(5)
        result[1] = 0x01; result[2] = 0x02; // 1: 2 (EC2)
        result[3] = 0x03; result[4] = 0x26; // 3: -7 (ES256)
        result[5] = (byte) 0x20; result[6] = 0x01; // -1: 1 (P-256)
        result[7] = (byte) 0x21; result[8] = 0x58; result[9] = 0x20; // -2: bstr(32)
        System.arraycopy(x, 0, result, 10, 32);
        result[42] = (byte) 0x22; result[43] = 0x58; result[44] = 0x20; // -3: bstr(32)
        System.arraycopy(y, 0, result, 45, 32);
        return result;
    }

    /** Attestation object {fmt:"none", attStmt:{}, authData:...}. */
    private static byte[] attestationObject(byte[] authData) {
        byte[] head = {
            (byte) 0xA3, // map(3)
            0x63, 'f', 'm', 't', // "fmt"
            0x64, 'n', 'o', 'n', 'e', // "none"
            0x67, 'a', 't', 't', 'S', 't', 'm', 't', // "attStmt" (7 huruf)
            (byte) 0xA0, // {}
            0x68, 'a', 'u', 't', 'h', 'D', 'a', 't', 'a', // "authData"
            0x59, (byte) (authData.length >> 8), (byte) (authData.length & 0xFF)
        };
        byte[] result = new byte[head.length + authData.length];
        System.arraycopy(head, 0, result, 0, head.length);
        System.arraycopy(authData, 0, result, head.length, authData.length);
        return result;
    }

    private byte[] clientDataJson(String type, String challengeB64Url) {
        String body =
                "{\"type\":\""
                        + type
                        + "\",\"challenge\":\""
                        + challengeB64Url
                        + "\",\"origin\":\""
                        + origin
                        + "\",\"crossOrigin\":false}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] sign(byte[] data) throws Exception {
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(data);
        return signature.sign();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Titik kurva memerlukan notasi 32 byte tanpa byte tanda depan (pad-nolong/singkat). */
    private static byte[] to32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] out = new byte[32];
        if (raw.length == 32) {
            System.arraycopy(raw, 0, out, 0, 32);
        } else if (raw.length == 33) {
            // Byte mengawal adalah tanda; buang
            System.arraycopy(raw, 1, out, 0, 32);
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String b64url(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }
}
