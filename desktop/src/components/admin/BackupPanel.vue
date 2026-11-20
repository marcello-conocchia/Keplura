<script setup lang="ts">
/**
 * PILOT 4e — Datensicherung in der Administration.
 * Zeigt den Stand der letzten GEPRÜFTEN Sicherung (scripts/backup.sh schreibt
 * die Statusdatei) und erklärt Sichern, regelmäßiges Sichern und
 * Wiederherstellen. Die Wiederherstellung bleibt bewusst ein Server-Befehl.
 */
import { computed, onMounted, ref } from "vue";
import { fetchBackupStatus, type BackupStatus } from "../../admin-api";
import AppIcon from "../ui/AppIcon.vue";
import Button from "../ui/Button.vue";

const status = ref<BackupStatus | null>(null);
const loadError = ref("");
const loading = ref(false);

async function load() {
  loading.value = true;
  loadError.value = "";
  const r = await fetchBackupStatus();
  loading.value = false;
  if (r.kind === "ok") status.value = r.value;
  else loadError.value = "Der Sicherungsstatus konnte nicht geladen werden.";
}
onMounted(load);

const last = computed(() => status.value?.lastBackup ?? null);
const tone = computed(() => (!last.value ? "none" : last.value.stale || !last.value.verified ? "warn" : "ok"));

function size(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toLocaleString("de-DE", { maximumFractionDigits: 1 })} GB`;
  if (bytes >= 1024 ** 2) return `${(bytes / 1024 ** 2).toLocaleString("de-DE", { maximumFractionDigits: 1 })} MB`;
  return `${Math.max(1, Math.round(bytes / 1024))} KB`;
}
function when(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "–" : d.toLocaleString("de-DE", { dateStyle: "medium", timeStyle: "short" });
}
function age(hours: number): string {
  if (hours < 1) return "vor weniger als einer Stunde";
  if (hours < 48) return `vor ${hours} Stunden`;
  return `vor ${Math.floor(hours / 24)} Tagen`;
}
</script>

<template>
  <section class="bp" data-testid="admin-backup-section">
    <div class="bp-status" :class="tone" data-testid="admin-backup-status">
      <span class="bp-ring" aria-hidden="true"></span>
      <div class="bp-status-text">
        <template v-if="loading">Sicherungsstatus wird geladen …</template>
        <template v-else-if="loadError">{{ loadError }}</template>
        <template v-else-if="!last">
          <strong>Noch keine Sicherung bekannt.</strong>
          <span>Richte die regelmäßige Sicherung unten ein. Danach erscheint hier ihr Stand.</span>
        </template>
        <template v-else>
          <strong>
            Letzte Sicherung {{ age(last.ageHours) }}
            <template v-if="last.stale"> – bitte prüfen, die tägliche Sicherung ist überfällig</template>
          </strong>
          <span>
            {{ when(last.finishedAt) }} · Datenbank {{ size(last.databaseBytes) }} · Dokumente {{ size(last.documentsBytes) }}
            · {{ last.verified ? "geprüft und lesbar" : "nicht geprüft" }}
          </span>
        </template>
      </div>
      <Button variant="icon" type="button" class="bp-reload" title="Aktualisieren" aria-label="Sicherungsstatus aktualisieren" @click="load">
        <AppIcon name="refresh" :size="13" />
      </Button>
    </div>

    <div class="bp-list">
      <div class="bp-item">
        <h3>Jetzt sichern</h3>
        <p>
          Eine Sicherung enthält immer beide Teile zusammen: die Datenbank und alle hochgeladenen Dokumente.
          Sie wird nach dem Erstellen automatisch auf Lesbarkeit geprüft. Auf dem Server ausführen:
        </p>
        <pre class="bp-cmd">sudo scripts/backup.sh --out /var/backups/keplura --keep 14</pre>
        <p class="bp-hint">
          Der Server wird dafür kurz angehalten (wenige Sekunden bis Minuten). Mit <code>--online</code>
          läuft er weiter; im seltenen Fall fehlt dann ein gerade hochgeladenes Dokument.
        </p>
      </div>

      <div class="bp-item">
        <h3>Automatisch jede Nacht sichern</h3>
        <p>Empfohlen: täglich um 2:30 Uhr, die letzten 14 Sicherungen behalten.</p>
        <pre class="bp-cmd">30 2 * * *  cd /opt/keplura &amp;&amp; scripts/backup.sh --out /var/backups/keplura --keep 14 --yes</pre>
        <p class="bp-hint">
          Als Cron-Eintrag des Benutzers anlegen, der Docker bedienen darf (<code>crontab -e</code>). Den Ordner
          <code>/var/backups/keplura</code> zusätzlich auf ein anderes Gerät oder ein NAS kopieren lassen.
        </p>
      </div>

      <div class="bp-item">
        <h3>Wiederherstellen</h3>
        <p>
          Eine Wiederherstellung ersetzt alle aktuellen Daten durch den Stand der gewählten Sicherung.
          Deshalb gibt es dafür bewusst keinen Knopf im Browser, sondern einen Befehl auf dem Server mit Rückfrage:
        </p>
        <pre class="bp-cmd">sudo scripts/restore.sh /var/backups/keplura/&lt;Name der Sicherung&gt;</pre>
        <p class="bp-hint">
          Vorher alle Nutzer informieren. Nach der Wiederherstellung meldet das Skript, ob Datenbank und Dokumente
          zusammenpassen. Ausführliche Anleitung: <code>deploy/README.md</code>, Abschnitt Backup und Restore.
        </p>
      </div>
    </div>
  </section>
</template>

<style scoped>
.bp {
  display: grid;
  gap: 20px;
}
.bp-status {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--ic-separator);
  border-radius: 8px;
}
.bp-ring {
  width: 12px;
  height: 12px;
  flex: none;
  border-radius: 50%;
  border: 2px solid var(--ic-text-3);
}
.bp-status.ok .bp-ring {
  border-color: var(--ic-status-ok);
  background: radial-gradient(circle, var(--ic-status-ok) 0 3px, transparent 3.5px);
}
.bp-status.warn .bp-ring {
  border-color: var(--ic-status-wait);
}
.bp-status-text {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 2px;
  font-size: 14px;
}
.bp-status-text span {
  font-size: 13px;
  color: var(--ic-text-3);
}
.bp-list {
  border: 1px solid var(--ic-separator);
  border-radius: 8px;
  overflow: hidden;
}
.bp-item {
  display: grid;
  gap: 8px;
  padding: 16px;
  border-top: 1px solid var(--ic-separator-faint);
}
.bp-item:first-child {
  border-top: 0;
}
.bp-item h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}
.bp-item p {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--ic-text-2);
}
.bp-hint {
  color: var(--ic-text-3) !important;
}
.bp-cmd {
  margin: 0;
  padding: 10px 12px;
  overflow-x: auto;
  border-radius: 6px;
  background: var(--ic-hover-tint);
  font: 12px/1.5 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  color: var(--ic-text);
}
code {
  font: 12px ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
</style>
