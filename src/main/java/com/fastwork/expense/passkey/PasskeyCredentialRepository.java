package com.fastwork.expense.passkey;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.data.PublicKeyCredentialType;

import lombok.RequiredArgsConstructor;

/**
 * Adapter antara tabel {@code passkeys} dan interface {@link CredentialRepository}
 * milik Yubico. User handle WebAuthn = {@code users.id} (UUID 16 byte); username = email.
 */
@Component
@RequiredArgsConstructor
public class PasskeyCredentialRepository implements CredentialRepository {

    private final PasskeyRepository passkeys;
    private final UserRepository users;

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(String username) {
        return users
                .findByEmail(username == null ? "" : username.trim())
                .map(user -> passkeys.findByUserIdOrderByCreatedAtAsc(user.getId()))
                .orElse(List.of())
                .stream()
                .map(PasskeyCredentialRepository::descriptorOf)
                .collect(Collectors.toSet());
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return users
                .findByEmail(username == null ? "" : username.trim())
                .map(user -> uuidToBytes(user.getId()));
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        UUID userId = uuidFromBytes(userHandle);
        if (userId == null) {
            return Optional.empty();
        }
        return users.findById(userId).map(User::getEmail);
    }

    @Override
    public Optional<RegisteredCredential> lookup(ByteArray credentialId, ByteArray userHandle) {
        UUID userId = uuidFromBytes(userHandle);
        if (userId == null) {
            return Optional.empty();
        }
        return passkeys
                .findByCredentialId(credentialId.getBytes())
                .filter(row -> row.getUserId().equals(userId))
                .map(PasskeyCredentialRepository::registeredOf);
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        return passkeys
                .findByCredentialId(credentialId.getBytes())
                .map(row -> Set.of(registeredOf(row)))
                .orElse(Set.of());
    }

    public static UUID uuidFromBytes(ByteArray bytes) {
        if (bytes == null || bytes.size() != 16) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes.getBytes());
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    public static ByteArray uuidToBytes(UUID id) {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
        return new ByteArray(buffer.array());
    }

    private static PublicKeyCredentialDescriptor descriptorOf(Passkey row) {
        return PublicKeyCredentialDescriptor.builder()
                .id(new ByteArray(row.getCredentialId()))
                .type(PublicKeyCredentialType.PUBLIC_KEY)
                .transports(parseTransports(row.getTransports()))
                .build();
    }

    private static RegisteredCredential registeredOf(Passkey row) {
        Set<AuthenticatorTransport> transports = parseTransports(row.getTransports());
        RegisteredCredential.RegisteredCredentialBuilder builder =
                RegisteredCredential.builder()
                        .credentialId(new ByteArray(row.getCredentialId()))
                        .userHandle(uuidToBytes(row.getUserId()))
                        .publicKeyCose(new ByteArray(row.getPublicKeyCose()));
        builder.signatureCount(row.getSignatureCount())
                .backupEligible(row.getBackupEligible())
                .backupState(row.getBackupState());
        if (!transports.isEmpty()) {
            builder.transports(transports);
        }
        return builder.build();
    }

    private static Set<AuthenticatorTransport> parseTransports(String csv) {
        Set<AuthenticatorTransport> result = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        for (String name : csv.split(",")) {
            try {
                // Nilai tersimpan adalah id WebAuthn huruf kecil ("usb"), sedangkan
                // valueOf() menuntut nama konstanta ("USB").
                result.add(AuthenticatorTransport.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                // Nama transport asing (versi baru) diabaikan — tidak memengaruhi verifikasi.
            }
        }
        return result;
    }

    public static String transportsToString(Set<AuthenticatorTransport> transports) {
        if (transports == null || transports.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (AuthenticatorTransport transport : transports) {
            names.add(transport.getId());
        }
        return String.join(",", names);
    }

    /**
     * Algoritma dari kunci publik COSE (angka kunci CBOR 3), mis. -7 = ES256.
     * COSE sudah diverifikasi Yubico; angka 3 hilang praktis mustahil — bawaan
     * default ES256 hanya untuk konsistensi.
     */
    static int algFromCose(byte[] cose) {
        JsonNode key;
        try {
            key = ALG_READER.readTree(cose);
        } catch (IOException ex) {
            throw new IllegalArgumentException("Kunci publik COSE tidak valid", ex);
        }
        return key.path("3").asInt(-7);
    }

    private static final ObjectMapper ALG_READER = new ObjectMapper(new CBORFactory());
}
