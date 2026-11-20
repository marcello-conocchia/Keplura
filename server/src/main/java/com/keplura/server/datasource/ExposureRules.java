package com.keplura.server.datasource;

import com.keplura.server.access.AccessFilter;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * RISK-01 — Regeln des Oversharing-Checks. Rein, ohne Zustand, damit sie
 * vollständig unit-getestet werden können.
 *
 * <p>„Breit“ ist ein Prinzipal, der praktisch die ganze Belegschaft umfasst.
 * „Sensibel“ ist ein Hinweis aus Pfad und Dateiname, KEINE Inhaltsprüfung:
 * die Regeln sollen dem Admin Kandidaten zeigen, nicht entscheiden.
 */
public final class ExposureRules {

    private ExposureRules() {
    }

    private static final Pattern DOMAIN_RID = Pattern.compile("^sid:S-1-5-21-\\d+-\\d+-\\d+-(\\d+)$");

    /** Lesbare Bezeichnung, wenn der Prinzipal praktisch alle umfasst; sonst {@code null}. */
    public static String broadLabel(String principal) {
        if (principal == null) return null;
        if (AccessFilter.EVERYONE.equals(principal)) return "Alle angemeldeten Personen";
        if (principal.startsWith("entra-tenant:")) return "Alle Personen der Microsoft-365-Organisation";
        switch (principal) {
            case "sid:S-1-5-32-545": return "Benutzer (lokal)";
            case "sid:S-1-5-32-546": return "Gäste (lokal)";
            case "sid:S-1-5-7": return "Anonym";
            case "posix-gid:100": return "Linux-Gruppe „users“";
            default: break;
        }
        var m = DOMAIN_RID.matcher(principal);
        if (m.matches()) {
            return switch (m.group(1)) {
                case "513" -> "Domänen-Benutzer";
                case "514" -> "Domänen-Gäste";
                default -> null;
            };
        }
        return null;
    }

    /** Kategorien sensibler Ablagen mit ihren Suchwörtern (bereits normalisiert, siehe {@link #normalize}). */
    static final Map<String, List<String>> CATEGORIES = categories();

    private static Map<String, List<String>> categories() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        // Bewusst NICHT: „zeugnis“ (Prüfzeugnis), „beurteilung“ (Risikobeurteilung),
        // „uebernahme“ (Datenübernahme) — in Industrie-Ablagen zu viele Fehltreffer.
        m.put("Personal und Gehalt", List.of("gehalt", "gehaelter", "lohn", "loehne", "lohnabrechnung", "lohnliste", "entgelt", "payroll",
                "salary", "personalakte", "personaldaten", "arbeitsvertrag", "kuendigung", "abmahnung",
                "aufhebungsvertrag", "arbeitszeugnis", "zwischenzeugnis", "bewerbung", "bewerber",
                "mitarbeitergespraech", "leistungsbeurteilung", "mitarbeiterbeurteilung", "zielvereinbarung"));
        m.put("Gesundheit", List.of("krank", "arbeitsunfaehig", "attest", "diagnose", "befund", "arztbrief",
                "schwerbehinder", "eingliederungsmanagement", "betriebsarzt"));
        m.put("Zugangsdaten", List.of("passwort", "passwoerter", "kennwort", "password", "zugangsdaten",
                "credentials", "logins", "tan-liste", "pin-liste", "lizenzschluessel"));
        m.put("Vertraulich", List.of("vertraulich", "geheim", "confidential", "streng vertraulich", "nda",
                "geschaeftsfuehrung", "vorstand", "aufsichtsrat", "betriebsrat", "due diligence"));
        m.put("Bank und Steuern", List.of("bankverbindung", "kontoauszug", "kontoauszuege", "iban", "kreditkarte",
                "steuerbescheid", "steuererklaerung", "steuer-id", "sozialversicherung", "pfaendung"));
        return m;
    }

    /** Kleinbuchstaben, Umlaute ausgeschrieben, Trennzeichen zu Leerzeichen. */
    static String normalize(String path) {
        String s = path.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return " " + s.replaceAll("[/\\\\_.]+", " ") + " ";
    }

    /** Kategorien, deren Suchwörter im Pfad vorkommen (Reihenfolge stabil). */
    public static Set<String> sensitiveCategories(String relativePath) {
        Set<String> found = new LinkedHashSet<>();
        if (relativePath == null) return found;
        String n = normalize(relativePath);
        for (var e : MATCHERS.entrySet()) {
            if (e.getValue().stream().anyMatch(p -> p.matcher(n).find())) found.add(e.getKey());
        }
        return found;
    }

    /**
     * Treffer-Regel je Suchwort: bis 4 Zeichen (nda, iban) nur als ganzes Wort; 5–6 Zeichen
     * als Wortanfang (gehalt trifft „Gehaltsliste“, nicht „Fettgehalt“); ab 7 Zeichen auch
     * innerhalb zusammengesetzter Wörter („Jahrespersonalakte“).
     */
    private static final Map<String, List<Pattern>> MATCHERS = matchers();

    private static Map<String, List<Pattern>> matchers() {
        Map<String, List<Pattern>> m = new LinkedHashMap<>();
        for (var e : CATEGORIES.entrySet()) {
            m.put(e.getKey(), e.getValue().stream().map(term -> {
                String q = Pattern.quote(term);
                if (term.length() <= 4) return Pattern.compile("[^a-z0-9]" + q + "[^a-z0-9]");
                if (term.length() <= 6) return Pattern.compile("[^a-z0-9]" + q);
                return Pattern.compile(q);
            }).toList());
        }
        return m;
    }

    /**
     * Ausschluss-Muster für genau diese Datei im Glob-Format des Filters (Kleinbuchstaben).
     * Glob-Sonderzeichen werden maskiert; Komma und Zeilenumbruch trennen Muster und werden
     * deshalb durch {@code ?} (genau ein Zeichen) ersetzt.
     */
    public static String excludePatternFor(String relativePath) {
        StringBuilder b = new StringBuilder();
        for (char ch : relativePath.toLowerCase(Locale.ROOT).toCharArray()) {
            switch (ch) {
                case '*', '?', '[', ']', '{', '}', '\\' -> b.append('\\').append(ch);
                case ',', '\n', '\r' -> b.append('?');
                default -> b.append(ch);
            }
        }
        return b.toString();
    }

    /** Ordner für die Zusammenfassung: die ersten zwei Ebenen. */
    static String folderOf(String relativePath) {
        String[] parts = relativePath.split("/");
        if (parts.length <= 1) return "(oberste Ebene)";
        if (parts.length == 2) return parts[0];
        return parts[0] + "/" + parts[1];
    }
}
