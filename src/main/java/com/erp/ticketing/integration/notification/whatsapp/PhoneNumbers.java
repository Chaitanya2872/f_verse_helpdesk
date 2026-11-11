package com.erp.ticketing.integration.notification.whatsapp;

import java.util.regex.Pattern;

/**
 * Turns a phone number as people store it into the international digits WhatsApp addresses
 * ({@code 919876543210}: country code first, no {@code +}), or rejects it.
 *
 * <ul>
 *   <li>{@code +91 98765-43210}, {@code 0091 9876543210}: already international - separators and the prefix go</li>
 *   <li>{@code 09876543210} or {@code 9876543210}: national - only accepted with a configured default country
 *       code, which replaces the leading trunk {@code 0}</li>
 *   <li>{@code 919876543210} (stored without {@code +}): with default country code 91, a value longer than ten
 *       digits that already starts with it is taken as international rather than prefixed twice</li>
 *   <li>anything that does not end up as 8-15 digits starting 1-9 (E.164): rejected, never guessed</li>
 * </ul>
 */
public final class PhoneNumbers {

    private static final Pattern E164_DIGITS = Pattern.compile("[1-9]\\d{7,14}");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\-().]");
    private static final Pattern ALLOWED = Pattern.compile("\\+?\\d+");

    private PhoneNumbers() {
    }

    /**
     * @param defaultCountryCode digits only, or blank for "numbers must already carry a country code"
     * @return international digits, or null if the value is not a usable number
     */
    public static String normalize(String raw, String defaultCountryCode) {
        if (raw == null) {
            return null;
        }
        String value = SEPARATORS.matcher(raw.trim()).replaceAll("");
        if (value.isEmpty() || !ALLOWED.matcher(value).matches()) {
            return null;
        }
        String digits;
        if (value.startsWith("+")) {
            digits = value.substring(1);
        } else if (value.startsWith("00")) {
            digits = value.substring(2);
        } else {
            String country = defaultCountryCode == null ? "" : defaultCountryCode.trim();
            if (country.isEmpty() || !country.matches("[1-9]\\d{0,2}")) {
                return null;
            }
            String national = value.startsWith("0") ? value.substring(1) : value;
            // A national number already prefixed with the country code (e.g. 919876543210 stored without '+').
            digits = national.startsWith(country) && national.length() > 10 ? national : country + national;
        }
        return E164_DIGITS.matcher(digits).matches() ? digits : null;
    }

    /** Last four digits only, for logs and admin views. */
    public static String mask(String phone) {
        if (phone == null || phone.length() <= 4) {
            return "****";
        }
        return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
    }
}
