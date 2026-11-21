package com.keplura.server.datasource;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class ContentPatternsTest {

    private static Map<String, Integer> counts(String text) {
        return ContentPatterns.scan(text).stream()
                .collect(Collectors.toMap(ContentPatterns.Hit::category, ContentPatterns.Hit::count));
    }

    @Test
    void ibanWithChecksumAndGroups() {
        var c = counts("Konto: DE89 3704 0044 0532 0130 00, zweites Konto GB82WEST12345698765432.");
        assertThat(c).containsEntry(ContentPatterns.IBAN, 2);
    }

    @Test
    void ibanWrongChecksumOrLengthIsIgnored() {
        assertThat(counts("DE89 3704 0044 0532 0130 01 und DE89370400440532013")).doesNotContainKey(ContentPatterns.IBAN);
    }

    @Test
    void sameValueCountsOnce() {
        var c = counts("DE89370400440532013000\nDE89 3704 0044 0532 0130 00\nDE89370400440532013000");
        assertThat(c).containsEntry(ContentPatterns.IBAN, 1);
    }

    @Test
    void ibanFollowedByWordIsStillFound() {
        assertThat(counts("IBAN DE89 3704 0044 0532 0130 00 EUR")).containsEntry(ContentPatterns.IBAN, 1);
    }

    @Test
    void creditCardsNeedPrefixAndLuhn() {
        var c = counts("Visa 4111 1111 1111 1111, MC 5500-0000-0000-0004, Amex 378282246310005");
        assertThat(c).containsEntry(ContentPatterns.CREDIT_CARD, 3);
        assertThat(counts("4111 1111 1111 1112 und 1234 5678 9012 3456")).doesNotContainKey(ContentPatterns.CREDIT_CARD);
    }

    @Test
    void digitsInsideLongerNumbersAreNotCounted() {
        assertThat(counts("Artikel 941111111111111111117 und Betrag 4111111111111111,50"))
                .doesNotContainKey(ContentPatterns.CREDIT_CARD);
    }

    @Test
    void germanTaxId() {
        var c = counts("Steuer-ID 86095742719, Partnerin 47 036 892 816");
        assertThat(c).containsEntry(ContentPatterns.TAX_ID, 2);
        assertThat(counts("86095742710 und 11111111111 und 01234567891")).doesNotContainKey(ContentPatterns.TAX_ID);
    }

    @Test
    void germanAndUsSocialSecurityNumbers() {
        var c = counts("RV-Nr. 65 170839 J 003, Kollege 12150780M013, US 123-45-6789");
        assertThat(c).containsEntry(ContentPatterns.SOCIAL_SECURITY, 3);
        assertThat(counts("65 170839 J 004 und 000-12-3456 und 666-12-3456 und 123-00-6789"))
                .doesNotContainKey(ContentPatterns.SOCIAL_SECURITY);
    }

    @Test
    void keywordsNeedThreeHitsGermanAndEnglish() {
        assertThat(counts("Das Bruttogehalt steht im Vertrag.")).doesNotContainKey(ContentPatterns.HR_TERMS);
        var c = counts("Personalnummer 4711, Bruttogehalt 4.200 €, Nettogehalt 2.800 €, Steuerklasse III");
        assertThat(c).containsEntry(ContentPatterns.HR_TERMS, 4);
        var en = counts("Payroll March: salary, gross pay and net pay per employee.");
        assertThat(en).containsEntry(ContentPatterns.HR_TERMS, 4);
        var h = counts("Krankmeldung vom 3.5., Diagnose laut Arztbrief, AU-Bescheinigung. Sick leave until Friday.");
        assertThat(h).containsEntry(ContentPatterns.HEALTH_TERMS, 4);
    }

    @Test
    void umlautsAreNormalised() {
        var c = counts("Arbeitsunfähig, Krankschreibung, Schwerbehinderung");
        assertThat(c).containsEntry(ContentPatterns.HEALTH_TERMS, 3);
    }

    @Test
    void ordinaryTextHasNoHits() {
        assertThat(ContentPatterns.scan("Die Anlage läuft mit 3000 U/min. Prüfzeugnis Nr. 2024-118, Tel. +49 30 1234567."))
                .isEmpty();
        assertThat(ContentPatterns.scan("")).isEmpty();
        assertThat(ContentPatterns.scan(null)).isEmpty();
    }

    @Test
    void resultHoldsOnlyCategoryAndCount() {
        List<ContentPatterns.Hit> hits = ContentPatterns.scan("IBAN DE89370400440532013000");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).toString()).doesNotContain("DE89").doesNotContain("3704");
    }

    // review findings

    @Test
    void nonBreakingSpacesAndLowercaseAreRecognised() {
        assertThat(counts("IBAN DE89\u00a03704\u00a00044\u00a00532\u00a00130\u00a000")).containsEntry(ContentPatterns.IBAN, 1);
        assertThat(counts("iban de89 3704 0044 0532 0130 00")).containsEntry(ContentPatterns.IBAN, 1);
        assertThat(counts("Karte 4111\u202f1111\u202f1111\u202f1111")).containsEntry(ContentPatterns.CREDIT_CARD, 1);
        assertThat(counts("rv 65170839j003")).containsEntry(ContentPatterns.SOCIAL_SECURITY, 1);
    }

    @Test
    void numberRightBeforeACardDoesNotHideIt() {
        assertThat(counts("Pos 12 4111 1111 1111 1111")).containsEntry(ContentPatterns.CREDIT_CARD, 1);
        assertThat(counts("Nr 7 86095742719")).containsEntry(ContentPatterns.TAX_ID, 1);
    }

    @Test
    void moreCardPrefixes() {
        assertThat(counts("Discover 6445000000000000, Diners 38120000000002"))
                .containsEntry(ContentPatterns.CREDIT_CARD, 2);
    }

    @Test
    void itTextIsNotHealthData() {
        assertThat(counts("Please be patient, see the attestation, then diagnose the bug."))
                .doesNotContainKey(ContentPatterns.HEALTH_TERMS);
        assertThat(counts("Attest vom Arzt, Diagnose, Arztbrief")).containsEntry(ContentPatterns.HEALTH_TERMS, 3);
    }
}
