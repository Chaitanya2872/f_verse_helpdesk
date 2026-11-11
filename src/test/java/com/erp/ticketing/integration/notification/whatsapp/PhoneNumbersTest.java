package com.erp.ticketing.integration.notification.whatsapp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @Test
    void internationalNumbersKeepTheirCountryCodeWhateverTheFormatting() {
        assertThat(PhoneNumbers.normalize("+91 98765 43210", "")).isEqualTo("919876543210");
        assertThat(PhoneNumbers.normalize("+91-98765-43210", "")).isEqualTo("919876543210");
        assertThat(PhoneNumbers.normalize("0091 (98765) 43210", "")).isEqualTo("919876543210");
        assertThat(PhoneNumbers.normalize("+1 (415) 555-0100", "91")).isEqualTo("14155550100");
    }

    @Test
    void nationalNumbersNeedAConfiguredCountryCodeAndAreNeverGuessed() {
        assertThat(PhoneNumbers.normalize("9876543210", "")).isNull();
        assertThat(PhoneNumbers.normalize("9876543210", null)).isNull();
        assertThat(PhoneNumbers.normalize("9876543210", "91")).isEqualTo("919876543210");
        assertThat(PhoneNumbers.normalize("09876543210", "91")).as("trunk 0 dropped").isEqualTo("919876543210");
        assertThat(PhoneNumbers.normalize("919876543210", "91")).as("not prefixed twice").isEqualTo("919876543210");
    }

    @Test
    void invalidValuesAreRejected() {
        assertThat(PhoneNumbers.normalize(null, "91")).isNull();
        assertThat(PhoneNumbers.normalize("", "91")).isNull();
        assertThat(PhoneNumbers.normalize("   ", "91")).isNull();
        assertThat(PhoneNumbers.normalize("not a number", "91")).isNull();
        assertThat(PhoneNumbers.normalize("98765x43210", "91")).isNull();
        assertThat(PhoneNumbers.normalize("+12", "")).as("too short").isNull();
        assertThat(PhoneNumbers.normalize("+1234567890123456", "")).as("too long").isNull();
        assertThat(PhoneNumbers.normalize("+0919876543210", "")).as("no leading zero country code").isNull();
        assertThat(PhoneNumbers.normalize("9876543210", "abc")).as("bad configured code").isNull();
    }

    @Test
    void maskingKeepsOnlyTheLastFourDigits() {
        assertThat(PhoneNumbers.mask("919876543210")).isEqualTo("********3210");
        assertThat(PhoneNumbers.mask(null)).isEqualTo("****");
    }
}
