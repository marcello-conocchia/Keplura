package com.keplura.server.datasource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RISK-02 — sensible Muster im Text einer Datei. Rein und ohne Zustand.
 *
 * <p>Ergebnis ist NUR Kategorie + Anzahl. Gefundene Werte verlassen diese Klasse
 * nie: sie werden nicht zurückgegeben, nicht geloggt und nicht gespeichert.
 * Ziffern-Kennungen zählen verschiedene Werte (dieselbe IBAN zehnmal = 1),
 * Schlüsselwörter zählen Vorkommen und werden erst ab {@link #MIN_TERM_HITS} gemeldet.
 */
public final class ContentPatterns {

    private ContentPatterns() {
    }

    public record Hit(String category, String label, int count) {
    }

    public static final String IBAN = "IBAN";
    public static final String CREDIT_CARD = "CREDIT_CARD";
    public static final String TAX_ID = "TAX_ID";
    public static final String SOCIAL_SECURITY = "SOCIAL_SECURITY";
    public static final String HR_TERMS = "HR_TERMS";
    public static final String HEALTH_TERMS = "HEALTH_TERMS";

    /** Schlüsselwörter allein sind ein schwaches Signal: erst ab so vielen Treffern melden. */
    static final int MIN_TERM_HITS = 3;

    // ------------------------------------------------------------------ IBAN

    /** IBAN-Länge je Land (SEPA und gängige weitere Länder, Stand SWIFT-Register). */
    static final Map<String, Integer> IBAN_LENGTHS = Map.ofEntries(
            Map.entry("AD", 24), Map.entry("AE", 23), Map.entry("AL", 28), Map.entry("AT", 20),
            Map.entry("BA", 20), Map.entry("BE", 16), Map.entry("BG", 22), Map.entry("BH", 22),
            Map.entry("CH", 21), Map.entry("CY", 28), Map.entry("CZ", 24), Map.entry("DE", 22),
            Map.entry("DK", 18), Map.entry("EE", 20), Map.entry("ES", 24), Map.entry("FI", 18),
            Map.entry("FO", 18), Map.entry("FR", 27), Map.entry("GB", 22), Map.entry("GI", 23),
            Map.entry("GL", 18), Map.entry("GR", 27), Map.entry("HR", 21), Map.entry("HU", 28),
            Map.entry("IE", 22), Map.entry("IL", 23), Map.entry("IS", 26), Map.entry("IT", 27),
            Map.entry("LI", 21), Map.entry("LT", 20), Map.entry("LU", 20), Map.entry("LV", 21),
            Map.entry("MC", 27), Map.entry("ME", 22), Map.entry("MK", 19), Map.entry("MT", 31),
            Map.entry("NL", 18), Map.entry("NO", 15), Map.entry("PL", 28), Map.entry("PT", 25),
            Map.entry("RO", 24), Map.entry("RS", 22), Map.entry("SA", 24), Map.entry("SE", 24),
            Map.entry("SI", 19), Map.entry("SK", 24), Map.entry("SM", 27), Map.entry("TR", 26),
            Map.entry("UA", 29), Map.entry("VA", 22), Map.entry("XK", 20));

    /** Ländercode + Prüfziffern, nicht mitten in einem Wort. */
    private static final Pattern IBAN_START = Pattern.compile("(?<![A-Za-z0-9])([A-Z]{2})(\\d{2})");

    /** Prüfsumme ISO 13616 (mod 97 == 1) auf der kompakten Form ohne Leerzeichen. */
    static boolean validIban(String compact) {
        Integer len = compact.length() >= 2 ? IBAN_LENGTHS.get(compact.substring(0, 2)) : null;
        if (len == null || compact.length() != len) return false;
        String r = compact.substring(4) + compact.substring(0, 4);
        int mod = 0;
        for (int i = 0; i < r.length(); i++) {
            char ch = r.charAt(i);
            int v;
            if (ch >= '0' && ch <= '9') v = ch - '0';
            else if (ch >= 'A' && ch <= 'Z') v = ch - 'A' + 10;
            else return false;
            mod = (v >= 10 ? mod * 100 + v : mod * 10 + v) % 97;
        }
        return mod == 1;
    }

    // ------------------------------------------------------- Kreditkarte

    /** 13–19 Ziffern, zwischen Ziffern höchstens ein Leerzeichen oder Bindestrich. */
    private static final Pattern CARD = Pattern.compile("(?<![\\d.,])\\d(?:[ -]?\\d){12,18}(?![\\d]|[.,]\\d)");

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
        }
        return sum % 10 == 0;
    }

    /** Bekannte Kartenpräfixe mit passender Länge — senkt Fehltreffer bei beliebigen Ziffernfolgen. */
    static boolean cardPrefix(String d) {
        int n = d.length();
        int p2 = Integer.parseInt(d.substring(0, 2));
        int p4 = Integer.parseInt(d.substring(0, 4));
        if (d.charAt(0) == '4') return n == 13 || n == 16 || n == 19;                 // Visa
        if ((p2 >= 51 && p2 <= 55) || (p4 >= 2221 && p4 <= 2720)) return n == 16;     // Mastercard
        if (p2 == 34 || p2 == 37) return n == 15;                                      // Amex
        int p3 = Integer.parseInt(d.substring(0, 3));
        if (p4 == 6011 || p2 == 65 || (p3 >= 644 && p3 <= 649)) return n >= 16 && n <= 19; // Discover
        if (p4 >= 3528 && p4 <= 3589) return n >= 16 && n <= 19;                       // JCB
        if (p2 == 36 || (p2 == 30 && d.charAt(2) <= '5')) return n == 14;              // Diners
        if (p2 == 38 || p2 == 39) return n == 14 || n == 16;                            // Diners International
        return false;
    }

    // ---------------------------------------------- Sozialversicherung

    /** DE-Rentenversicherungsnummer: 2 Ziffern Bereich, TTMMJJ, Buchstabe, 2 Ziffern Serie, Prüfziffer. */
    private static final Pattern RVNR = Pattern.compile(
            "(?<![A-Za-z0-9])(\\d{2}) ?(\\d{6}) ?([A-Za-z]) ?(\\d{2}) ?(\\d)(?![A-Za-z0-9])");

    static boolean validRvnr(String area, String birth, char letter, String serial, int check) {
        int day = Integer.parseInt(birth.substring(0, 2));
        int month = Integer.parseInt(birth.substring(2, 4));
        // Tag 01–31; bei erschöpften Seriennummern wird 50 addiert (51–81)
        if (!((day >= 1 && day <= 31) || (day >= 51 && day <= 81)) || month < 1 || month > 12) return false;
        String l = String.format(Locale.ROOT, "%02d", Character.toUpperCase(letter) - 'A' + 1);
        String digits = area + birth + l + serial;
        int[] w = {2, 1, 2, 5, 7, 1, 2, 1, 2, 1, 2, 1};
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            int p = (digits.charAt(i) - '0') * w[i];
            sum += p / 10 + p % 10;
        }
        return sum % 10 == check;
    }

    /** US-SSN nur im typischen Format mit Bindestrichen, mit den Vergaberegeln der SSA. */
    private static final Pattern SSN = Pattern.compile("(?<![\\d-])(\\d{3})-(\\d{2})-(\\d{4})(?![\\d-])");

    static boolean validSsn(String area, String group, String serial) {
        int a = Integer.parseInt(area);
        return a != 0 && a != 666 && a < 900 && !"00".equals(group) && !"0000".equals(serial);
    }

    // ------------------------------------------------------- Steuer-ID

    private static final Pattern TAX = Pattern.compile("(?<![\\d.,])(\\d{2}) ?(\\d{3}) ?(\\d{3}) ?(\\d{3})(?![\\d]|[.,]\\d)");

    /** Steuerliche Identifikationsnummer (DE): Ziffernregel + Prüfziffer ISO 7064 MOD 11,10. */
    static boolean validTaxId(String d) {
        if (d.length() != 11 || d.charAt(0) == '0') return false;
        int[] freq = new int[10];
        for (int i = 0; i < 10; i++) freq[d.charAt(i) - '0']++;
        int repeated = 0;
        for (int f : freq) {
            if (f > 3) return false;
            if (f >= 2) repeated++;
        }
        if (repeated != 1) return false;
        int product = 10;
        for (int i = 0; i < 10; i++) {
            int sum = (d.charAt(i) - '0' + product) % 10;
            if (sum == 0) sum = 10;
            product = (sum * 2) % 11;
        }
        int check = 11 - product;
        if (check == 10) check = 0;
        return check == d.charAt(10) - '0';
    }

    // --------------------------------------------------- Schlüsselwörter

    static final List<String> HR_WORDS = List.of(
            "bruttogehalt", "nettogehalt", "grundgehalt", "jahresgehalt", "monatsgehalt", "gehaltsabrechnung",
            "gehaltserhoehung", "lohnabrechnung", "entgeltabrechnung", "lohnsteuer", "steuerklasse",
            "personalnummer", "sozialversicherungsnummer", "rentenversicherungsnummer", "krankenkasse",
            "arbeitsvertrag", "kuendigung", "abmahnung", "aufhebungsvertrag", "geburtsdatum",
            "salary", "payroll", "gross pay", "net pay", "payslip", "pay slip", "employee id", "date of birth",
            "social security", "termination letter", "performance review");

    static final List<String> HEALTH_WORDS = List.of(
            "diagnose", "diagnosis", "krankmeldung", "krankschreibung", "arbeitsunfaehig", "attest!", "atteste", "befund",
            "arztbrief", "icd-10", "schwerbehinder", "grad der behinderung", "medikation", "eingliederungsmanagement",
            "betriebsarzt", "medication", "sick leave", "sick note", "medical record", "medical certificate",
            "disability");

    private static final Pattern HR = wordsPattern(HR_WORDS);
    private static final Pattern HEALTH = wordsPattern(HEALTH_WORDS);

    /**
     * Wortanfang: „Diagnose“ trifft „Diagnosen“, „Bruttogehalt“ nicht in „xbruttogehalt“.
     * Endet ein Begriff auf „!“, zählt nur das ganze Wort („attest“, nicht „attestation“).
     */
    private static Pattern wordsPattern(List<String> words) {
        StringBuilder b = new StringBuilder("(?<![a-z0-9])(?:");
        for (int i = 0; i < words.size(); i++) {
            if (i > 0) b.append('|');
            String w = words.get(i);
            boolean whole = w.endsWith("!");
            if (whole) w = w.substring(0, w.length() - 1);
            b.append(Pattern.quote(w).replace(" ", "\\E\\s+\\Q"));
            if (whole) b.append("(?![a-z])");
        }
        return Pattern.compile(b.append(')').toString());
    }

    static String normalize(String text) {
        return text.toLowerCase(Locale.GERMAN)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    // ---------------------------------------------------------- Prüfung

    /** Kategorien mit Treffern in fester Reihenfolge; leer = nichts gefunden. */
    public static List<Hit> scan(String text) {
        List<Hit> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        text = plainSpaces(text);
        StringBuilder masked = new StringBuilder(text);

        add(out, IBAN, "IBAN", ibans(text, masked));
        add(out, CREDIT_CARD, "Kreditkartennummer", digitIds(CARD, masked,
                d -> d.length() >= 13 && d.length() <= 19 && cardPrefix(d) && luhn(d)));
        add(out, SOCIAL_SECURITY, "Sozialversicherungsnummer", socialSecurity(masked));
        add(out, TAX_ID, "Steuer-ID", digitIds(TAX, masked, ContentPatterns::validTaxId));
        String norm = normalize(text);
        int hr = count(HR, norm);
        if (hr >= MIN_TERM_HITS) add(out, HR_TERMS, "Begriffe zu Personal und Gehalt", hr);
        int health = count(HEALTH, norm);
        if (health >= MIN_TERM_HITS) add(out, HEALTH_TERMS, "Begriffe zu Gesundheit", health);
        return out;
    }

    /** Geschützte und schmale Leerzeichen (häufig in PDF/Excel) wie normale behandeln; Länge bleibt gleich. */
    static String plainSpaces(String text) {
        return text.replace('\u00a0', ' ').replace('\u202f', ' ').replace('\u2007', ' ');
    }

    /** Großbuchstaben Zeichen für Zeichen, damit Positionen gleich bleiben (für „de89 …“). */
    private static String upperSameLength(String text) {
        char[] c = text.toCharArray();
        for (int i = 0; i < c.length; i++) c[i] = Character.toUpperCase(c[i]);
        return new String(c);
    }

    private static void add(List<Hit> out, String category, String label, int n) {
        if (n > 0) out.add(new Hit(category, label, n));
    }

    private static int ibans(String original, StringBuilder masked) {
        String text = upperSameLength(original);
        Set<String> seen = new HashSet<>();
        Matcher m = IBAN_START.matcher(text);
        int from = 0;
        while (from < text.length() && m.find(from)) {
            Integer len = IBAN_LENGTHS.get(m.group(1));
            int end = m.end();
            if (len != null) {
                StringBuilder compact = new StringBuilder(m.group(1)).append(m.group(2));
                int i = end;
                // Gruppen dürfen durch ein Leerzeichen getrennt sein; Kleinbuchstaben gehören nicht dazu
                while (compact.length() < len && i < text.length()) {
                    char ch = text.charAt(i);
                    if ((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z')) {
                        compact.append(ch);
                        i++;
                    } else if (ch == ' ' && i + 1 < text.length() && Character.isLetterOrDigit(text.charAt(i + 1))
                            && Character.isLetterOrDigit(text.charAt(i - 1))) {
                        i++;
                    } else {
                        break;
                    }
                }
                // direkt danach darf kein weiteres Zeichen der Kennung folgen
                boolean boundary = i >= text.length() || !Character.isLetterOrDigit(text.charAt(i));
                if (compact.length() == len && boundary && validIban(compact.toString())) {
                    seen.add(compact.toString());
                    blank(masked, m.start(), i);
                    end = i;
                }
            }
            from = Math.max(end, m.start() + 1);
        }
        return seen.size();
    }

    private interface DigitCheck {
        boolean test(String digits);
    }

    private static int digitIds(Pattern p, StringBuilder masked, DigitCheck check) {
        Set<String> seen = new HashSet<>();
        Matcher m = p.matcher(masked);
        List<int[]> hits = new ArrayList<>();
        int from = 0;
        while (from < masked.length() && m.find(from)) {
            String digits = m.group().replaceAll("[^0-9]", "");
            if (check.test(digits) && standsAlone(masked, m.start(), m.end())) {
                seen.add(digits);
                hits.add(new int[]{m.start(), m.end()});
                from = m.end();
            } else {
                from = m.start() + 1; // „Pos 12 4111 …“: ab der nächsten Ziffernfolge neu versuchen
            }
        }
        for (int[] h : hits) blank(masked, h[0], h[1]);
        return seen.size();
    }

    /**
     * Treffer zählt nur, wenn er nicht Teil einer längeren gruppierten Nummer ist: direkt davor oder
     * danach (getrennt durch Leerzeichen/Bindestrich) steht keine Gruppe mit 4 oder mehr Ziffern.
     * Kurze Zahlen daneben („Pos 12“, Ablaufdatum „12/27“) stören nicht.
     */
    private static boolean standsAlone(CharSequence s, int start, int end) {
        if (end + 1 < s.length() && (s.charAt(end) == ' ' || s.charAt(end) == '-')) {
            int n = 0;
            for (int i = end + 1; i < s.length() && Character.isDigit(s.charAt(i)); i++) n++;
            if (n >= 4) return false;
        }
        if (start >= 2 && (s.charAt(start - 1) == ' ' || s.charAt(start - 1) == '-')) {
            int n = 0;
            for (int i = start - 2; i >= 0 && Character.isDigit(s.charAt(i)); i--) n++;
            if (n >= 4) return false;
        }
        return true;
    }

    private static int socialSecurity(StringBuilder masked) {
        Set<String> seen = new HashSet<>();
        List<int[]> hits = new ArrayList<>();
        Matcher m = RVNR.matcher(masked);
        while (m.find()) {
            if (validRvnr(m.group(1), m.group(2), m.group(3).charAt(0), m.group(4), m.group(5).charAt(0) - '0')) {
                seen.add("de:" + m.group(1) + m.group(2) + m.group(3).toUpperCase(Locale.ROOT) + m.group(4) + m.group(5));
                hits.add(new int[]{m.start(), m.end()});
            }
        }
        for (int[] h : hits) blank(masked, h[0], h[1]);
        Matcher s = SSN.matcher(masked);
        while (s.find()) {
            if (validSsn(s.group(1), s.group(2), s.group(3))) {
                seen.add("us:" + s.group(1) + s.group(2) + s.group(3));
                hits.add(new int[]{s.start(), s.end()});
            }
        }
        for (int[] h : hits) blank(masked, h[0], h[1]);
        return seen.size();
    }

    /** Erkannte Kennungen ausblenden, damit sie nicht zusätzlich als andere Kategorie zählen. */
    private static void blank(StringBuilder b, int from, int to) {
        for (int i = from; i < to; i++) b.setCharAt(i, ' ');
    }

    private static int count(Pattern p, String text) {
        int n = 0;
        Matcher m = p.matcher(text);
        while (m.find()) n++;
        return n;
    }
}
