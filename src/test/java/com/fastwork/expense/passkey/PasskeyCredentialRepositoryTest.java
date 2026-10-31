package com.fastwork.expense.passkey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fastwork.expense.user.User;
import com.fastwork.expense.user.UserRepository;
import com.fastwork.expense.user.UserRole;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;

/** Unit test adapter repositori passkey (pemetaan baris DB <-> objek Yubico). */
@ExtendWith(MockitoExtension.class)
class PasskeyCredentialRepositoryTest {

    @Mock private PasskeyRepository passkeys;
    @Mock private UserRepository users;
    @InjectMocks private PasskeyCredentialRepository repository;

    private static final User USER =
            new User(
                    UUID.fromString("01a191c7-0000-7000-8000-000000000000"),
                    "pemilik@example.com",
                    "hash",
                    UserRole.OWNER);

    private static Passkey passkey(String transports) {
        Passkey row =
                new Passkey(
                        UUID.randomUUID(),
                        USER.getId(),
                        new byte[] {1, 2, 3, 4},
                        new byte[] {(byte) 0xA1, 0x03, 0x26});
        row.setSignatureCount(7L);
        row.setBackupEligible(true);
        row.setBackupState(true);
        row.setTransports(transports);
        return row;
    }

    @Test
    void uuidFromBytesRejectsNullAndWrongLength() {
        assertThat(PasskeyCredentialRepository.uuidFromBytes(null)).isNull();
        assertThat(PasskeyCredentialRepository.uuidFromBytes(new ByteArray(new byte[15]))).isNull();
        assertThat(PasskeyCredentialRepository.uuidFromBytes(new ByteArray(new byte[17]))).isNull();
    }

    @Test
    void uuidToBytesRoundTrips() {
        UUID id = UUID.fromString("01a191c7-1234-7000-8000-000000000abc");
        ByteArray bytes = PasskeyCredentialRepository.uuidToBytes(id);

        assertThat(bytes.size()).isEqualTo(16);
        assertThat(PasskeyCredentialRepository.uuidFromBytes(bytes)).isEqualTo(id);
    }

    @Test
    void algFromCoseReadsAlgorithm() {
        assertThat(PasskeyCredentialRepository.algFromCose(new byte[] {(byte) 0xA1, 0x03, 0x26}))
                .isEqualTo(-7);
    }

    @Test
    void algFromCoseDefaultsWhenKeyMissing() {
        // Map CBOR {1: 2} tanpa angka kunci 3.
        assertThat(PasskeyCredentialRepository.algFromCose(new byte[] {(byte) 0xA1, 0x01, 0x02}))
                .isEqualTo(-7);
    }

    @Test
    void algFromCoseRejectsMalformedBytes() {
        assertThatThrownBy(() -> PasskeyCredentialRepository.algFromCose(new byte[] {(byte) 0xFF}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transportsToStringHandlesNullEmptyAndValues() {
        assertThat(PasskeyCredentialRepository.transportsToString(null)).isEmpty();
        assertThat(PasskeyCredentialRepository.transportsToString(Set.of())).isEmpty();
        assertThat(
                        PasskeyCredentialRepository.transportsToString(
                                Set.of(AuthenticatorTransport.USB)))
                .isEqualTo("usb");
    }

    @Test
    void getCredentialIdsForUsernameNormalisesAndMapsRows() {
        when(users.findByEmail("")).thenReturn(Optional.empty());
        assertThat(repository.getCredentialIdsForUsername(null)).isEmpty();

        Passkey row = passkey("usb,nfc,transport-asing");
        when(users.findByEmail("pemilik@example.com")).thenReturn(Optional.of(USER));
        when(passkeys.findByUserIdOrderByCreatedAtAsc(USER.getId())).thenReturn(List.of(row));

        Set<PublicKeyCredentialDescriptor> ids =
                repository.getCredentialIdsForUsername("  pemilik@example.com  ");

        assertThat(ids).hasSize(1);
        PublicKeyCredentialDescriptor descriptor = ids.iterator().next();
        assertThat(descriptor.getId().getBytes()).containsExactly(1, 2, 3, 4);
        assertThat(descriptor.getTransports())
                .hasValueSatisfying(
                        transports ->
                                assertThat(transports)
                                        .containsExactlyInAnyOrder(
                                                AuthenticatorTransport.USB,
                                                AuthenticatorTransport.NFC));
    }

    @Test
    void getCredentialIdsForUsernameWithEmptyTransports() {
        Passkey row = passkey(null);
        when(users.findByEmail("pemilik@example.com")).thenReturn(Optional.of(USER));
        when(passkeys.findByUserIdOrderByCreatedAtAsc(USER.getId())).thenReturn(List.of(row));

        assertThat(repository.getCredentialIdsForUsername("pemilik@example.com"))
                .singleElement()
                .satisfies(
                        descriptor ->
                                assertThat(descriptor.getTransports())
                                        .hasValueSatisfying(
                                                transports -> assertThat(transports).isEmpty()));
    }

    @Test
    void getUserHandleForUsernameUsesUuidBytes() {
        when(users.findByEmail("pemilik@example.com")).thenReturn(Optional.of(USER));

        assertThat(repository.getUserHandleForUsername("pemilik@example.com"))
                .hasValueSatisfying(
                        handle ->
                                assertThat(PasskeyCredentialRepository.uuidFromBytes(handle))
                                        .isEqualTo(USER.getId()));

        when(users.findByEmail("")).thenReturn(Optional.empty());
        assertThat(repository.getUserHandleForUsername(null)).isEmpty();
    }

    @Test
    void getUsernameForUserHandleRejectsInvalidHandleAndMissingUser() {
        assertThat(repository.getUsernameForUserHandle(new ByteArray(new byte[4]))).isEmpty();

        ByteArray handle = PasskeyCredentialRepository.uuidToBytes(USER.getId());
        when(users.findById(USER.getId())).thenReturn(Optional.empty());
        assertThat(repository.getUsernameForUserHandle(handle)).isEmpty();

        when(users.findById(USER.getId())).thenReturn(Optional.of(USER));
        assertThat(repository.getUsernameForUserHandle(handle)).contains("pemilik@example.com");
    }

    @Test
    void lookupRejectsMismatchedUserAndReturnsRegisteredCredential() {
        Passkey row = passkey("usb");
        ByteArray credentialId = new ByteArray(row.getCredentialId());
        ByteArray otherHandle = PasskeyCredentialRepository.uuidToBytes(UUID.randomUUID());

        when(passkeys.findByCredentialId(row.getCredentialId())).thenReturn(Optional.of(row));
        assertThat(repository.lookup(credentialId, otherHandle)).isEmpty();

        ByteArray handle = PasskeyCredentialRepository.uuidToBytes(USER.getId());
        RegisteredCredential credential =
                repository.lookup(credentialId, handle).orElseThrow();

        assertThat(credential.getCredentialId()).isEqualTo(credentialId);
        assertThat(credential.getUserHandle()).isEqualTo(handle);
        assertThat(credential.getSignatureCount()).isEqualTo(7L);
        assertThat(credential.isBackupEligible()).contains(true);
        assertThat(credential.isBackedUp()).contains(true);
        assertThat(credential.getTransports())
                .hasValueSatisfying(
                        transports -> assertThat(transports).contains(AuthenticatorTransport.USB));
    }

    @Test
    void lookupRejectsInvalidUserHandle() {
        assertThat(repository.lookup(new ByteArray(new byte[] {1}), new ByteArray(new byte[1])))
                .isEmpty();
    }

    @Test
    void lookupAllMapsSingleRow() {
        Passkey row = passkey("");
        ByteArray credentialId = new ByteArray(row.getCredentialId());

        when(passkeys.findByCredentialId(row.getCredentialId())).thenReturn(Optional.of(row));
        assertThat(repository.lookupAll(credentialId))
                .singleElement()
                .satisfies(
                        credential -> {
                            assertThat(credential.getCredentialId()).isEqualTo(credentialId);
                            assertThat(credential.getTransports()).isEmpty();
                        });

        when(passkeys.findByCredentialId(row.getCredentialId())).thenReturn(Optional.empty());
        assertThat(repository.lookupAll(credentialId)).isEmpty();
    }

    @Test
    void uuidBytesUseBigEndianLayout() {
        UUID id = new UUID(0x0102030405060708L, 0x1112131415161718L);
        ByteBuffer expected = ByteBuffer.allocate(16);
        expected.putLong(id.getMostSignificantBits());
        expected.putLong(id.getLeastSignificantBits());

        assertThat(PasskeyCredentialRepository.uuidToBytes(id).getBytes())
                .containsExactly(expected.array());
    }
}
