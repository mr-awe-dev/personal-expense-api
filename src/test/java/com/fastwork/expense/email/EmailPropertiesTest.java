package com.fastwork.expense.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Coverage setter/getter EmailProperties (trim terhadap null/kosong, nilai utama). */
class EmailPropertiesTest {

    @Test
    void defaultsAreDisabledWithEmptyCredentials() {
        EmailProperties properties = new EmailProperties();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getFrom()).isEmpty();
        assertThat(properties.getFromName()).isEqualTo("Personal Expense");
    }

    @Test
    void settersTrimAndMapNullToEmpty() {
        EmailProperties properties = new EmailProperties();
        properties.setEnabled(true);
        properties.setApiKey(" kunci-berisi-  ");
        properties.setFrom(" pengirim@contoh.com ");
        properties.setFromName(" Aplikasi Uji ");

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getApiKey()).isEqualTo("kunci-berisi-");
        assertThat(properties.getFrom()).isEqualTo("pengirim@contoh.com");
        assertThat(properties.getFromName()).isEqualTo("Aplikasi Uji");

        properties.setApiKey(null);
        properties.setFrom(null);
        properties.setFromName(null);
        assertThat(properties.getApiKey()).isEmpty();
        assertThat(properties.getFrom()).isEmpty();
        assertThat(properties.getFromName()).isEmpty();
    }
}
