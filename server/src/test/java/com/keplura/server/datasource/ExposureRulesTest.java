package com.keplura.server.datasource;

import org.junit.jupiter.api.Test;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExposureRulesTest {

    @Test
    void wellKnownBroadPrincipalsAreRecognised() {
        assertThat(ExposureRules.broadLabel("everyone")).isEqualTo("Alle angemeldeten Personen");
        assertThat(ExposureRules.broadLabel("sid:S-1-5-21-111-222-333-513")).isEqualTo("Domänen-Benutzer");
        assertThat(ExposureRules.broadLabel("sid:S-1-5-21-111-222-333-514")).isEqualTo("Domänen-Gäste");
        assertThat(ExposureRules.broadLabel("sid:S-1-5-32-545")).isEqualTo("Benutzer (lokal)");
        assertThat(ExposureRules.broadLabel("entra-tenant:abc")).contains("Microsoft-365");
        assertThat(ExposureRules.broadLabel("posix-gid:100")).contains("users");
    }

    @Test
    void ordinaryGroupsAndUsersAreNotBroad() {
        assertThat(ExposureRules.broadLabel("sid:S-1-5-21-111-222-333-1105")).isNull(); // Vertrieb
        assertThat(ExposureRules.broadLabel("sid:S-1-5-21-111-222-333-512")).isNull();  // Domänen-Admins
        assertThat(ExposureRules.broadLabel("posix-gid:2001")).isNull();
        assertThat(ExposureRules.broadLabel("posix-uid:1001")).isNull();
        assertThat(ExposureRules.broadLabel("sid:S-1-5-21-1-2-513")).isNull(); // falsche Form
        assertThat(ExposureRules.broadLabel(null)).isNull();
    }

    @Test
    void sensitivePathsAreFlaggedWithUmlautsAndSeparators() {
        assertThat(ExposureRules.sensitiveCategories("Personal/Gehälter_2025.xlsx")).containsExactly("Personal und Gehalt");
        assertThat(ExposureRules.sensitiveCategories("Personal/Gehaltsliste_2025.pdf")).containsExactly("Personal und Gehalt");
        assertThat(ExposureRules.sensitiveCategories("hr/Kündigung_Müller.docx")).containsExactly("Personal und Gehalt");
        assertThat(ExposureRules.sensitiveCategories("it/Passwörter Server.txt")).containsExactly("Zugangsdaten");
        assertThat(ExposureRules.sensitiveCategories("buchhaltung/Bankverbindung-Lieferanten.csv")).containsExactly("Bank und Steuern");
        assertThat(ExposureRules.sensitiveCategories("gf/NDA Kunde.pdf")).containsExactly("Vertraulich");
        assertThat(ExposureRules.sensitiveCategories("hr/Krankmeldungen.csv")).containsExactly("Gesundheit");
    }

    @Test
    void typicalIndustryTermsDoNotTrigger() {
        assertThat(ExposureRules.sensitiveCategories("qs/Materialprüfzeugnis 3.1.pdf")).isEmpty();
        assertThat(ExposureRules.sensitiveCategories("sicherheit/Risikobeurteilung Presse.docx")).isEmpty();
        assertThat(ExposureRules.sensitiveCategories("labor/Fettgehalt Probe 7.csv")).isEmpty();
        assertThat(ExposureRules.sensitiveCategories("technik/Steuerung SPS Handbuch.pdf")).isEmpty();
        assertThat(ExposureRules.sensitiveCategories("it/Datenübernahme ERP.md")).isEmpty();
        assertThat(ExposureRules.sensitiveCategories("vertrieb/Agenda.md")).isEmpty(); // „nda“ nur als ganzes Wort
    }

    @Test
    void excludePatternMatchesExactlyThisFileEvenWithGlobCharacters() {
        // ohne * und ? im Pfad, damit der Test auch unter Windows läuft (dort im Dateinamen verboten)
        String path = "Personal/[alt]/Gehalt {2025}, Kopie.pdf";
        String pattern = ExposureRules.excludePatternFor(path);
        var m = FileSystems.getDefault().getPathMatcher("glob:" + pattern.toLowerCase());
        assertThat(m.matches(Path.of(path.toLowerCase()))).isTrue();
        assertThat(m.matches(Path.of("personal/a/gehalt 2, kopie.pdf"))).isFalse();
        assertThat(m.matches(Path.of("personal/[alt]/gehalt {2025}, kopie-anders.pdf"))).isFalse();
        assertThat(ExposureRules.excludePatternFor("a*b?.pdf")).isEqualTo("a\\*b\\?.pdf");
        assertThat(pattern).doesNotContain(",");
        assertThat(DataSourceSyncService.split(pattern)).containsExactly(pattern);
    }

    @Test
    void folderSummaryUsesTwoLevels() {
        assertThat(ExposureRules.folderOf("a.pdf")).isEqualTo("(oberste Ebene)");
        assertThat(ExposureRules.folderOf("hr/a.pdf")).isEqualTo("hr");
        assertThat(ExposureRules.folderOf("hr/2025/x/a.pdf")).isEqualTo("hr/2025");
    }

    @Test
    void denyWinsOverBroadAllow() {
        var acl = new SourceConnector.FileAcl(Set.of("everyone", "sid:S-1-5-21-1-2-3-513"), Set.of("sid:S-1-5-21-1-2-3-513"));
        assertThat(ExposureScanService.broadPrincipals(acl)).containsExactly("everyone");
        var denied = new SourceConnector.FileAcl(Set.of("sid:S-1-5-21-1-2-3-513"), Set.of("everyone"));
        assertThat(ExposureScanService.broadPrincipals(denied)).isEmpty();
        assertThat(ExposureScanService.broadPrincipals(SourceConnector.FileAcl.unknown())).isEmpty();
    }
}
