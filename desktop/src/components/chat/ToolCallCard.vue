<script setup lang="ts">
/**
 * TU-02 — one tool call card under a chat answer: tool/source, risk class, readable arguments,
 * honest status and the result (foreign content, plain text) or a generic error. A pending
 * CRITICAL call shows Confirm / Reject; the server binds and executes exactly the shown call.
 */
import { computed } from "vue";
import Button from "../ui/Button.vue";
import StatusPill from "../ui/StatusPill.vue";
import { tr, serverText, dateLocale } from "../../i18n";
import {
  argumentEntries, canDecide, hiddenArgumentCount, riskLabel, statusMeta, type ToolCallCard,
} from "../../tool-cards";

const props = defineProps<{ card: ToolCallCard; busy: boolean; error?: string | null }>();
const emit = defineEmits<{ decide: [decision: "confirm" | "reject"] }>();

const meta = computed(() => statusMeta(props.card.status));
const args = computed(() => argumentEntries(props.card.arguments));
const hidden = computed(() => hiddenArgumentCount(props.card.arguments));
const decidable = computed(() => canDecide(props.card));
const headingId = computed(() => `tool-call-${props.card.id}`);
const deadline = computed(() =>
  props.card.expiresAt ? new Date(props.card.expiresAt).toLocaleTimeString(dateLocale(), { hour: "2-digit", minute: "2-digit" }) : "");
</script>

<template>
  <section
    class="tool-card"
    :class="{ pending: decidable }"
    :aria-labelledby="headingId"
    data-testid="chat-tool-call"
    :data-status="card.status"
  >
    <header class="tool-card-head">
      <h4 :id="headingId" class="tool-card-title">
        <span class="tool-card-kicker">{{ $t("Werkzeug") }}</span>
        <code>{{ card.tool }}</code>
        <span class="tool-card-source">· {{ card.source }}</span>
      </h4>
      <span class="tool-card-pills">
        <StatusPill :tone="card.risk === 'CRITICAL' ? 'error' : card.risk === 'WRITE' ? 'wait' : 'neutral'" data-testid="chat-tool-call-risk">{{ $t(riskLabel(card.risk)) }}</StatusPill>
        <StatusPill :tone="meta.tone" data-testid="chat-tool-call-status">{{ $t(meta.label) }}</StatusPill>
      </span>
    </header>

    <dl v-if="args.length" class="tool-card-args">
      <template v-for="a in args" :key="a.key">
        <dt>{{ a.key }}</dt>
        <dd>{{ a.value }}</dd>
      </template>
    </dl>
    <p v-else class="tool-card-note">{{ $t("Ohne Eingaben.") }}</p>
    <p v-if="hidden > 0" class="tool-card-note">{{ $t("{0} weitere Eingaben nicht angezeigt.", hidden) }}</p>

    <div v-if="decidable" class="tool-card-confirm" role="group" :aria-label="$t('Aufruf bestätigen oder ablehnen')">
      <p class="tool-card-note">
        {{ $t("Dieser Aufruf wird erst ausgeführt, wenn du ihn bestätigst. Bestätigt wird genau dieser Aufruf mit diesen Eingaben, bis {0}.", deadline) }}
      </p>
      <div class="tool-card-actions">
        <Button variant="danger" size="compact" :busy="busy" :disabled="busy" data-testid="chat-tool-call-confirm" @click="emit('decide', 'confirm')">{{ $t("Ausführen") }}</Button>
        <Button size="compact" :disabled="busy" data-testid="chat-tool-call-reject" @click="emit('decide', 'reject')">{{ $t("Ablehnen") }}</Button>
      </div>
    </div>

    <p v-if="error" class="tool-card-alert" role="alert">{{ error }}</p>
    <p v-if="card.status === 'UNKNOWN'" class="tool-card-note">{{ $t("Der Ausgang ist unklar. Der Aufruf wird nicht automatisch wiederholt; bitte im angebundenen System prüfen.") }}</p>
    <p v-if="card.error" class="tool-card-note" data-testid="chat-tool-call-error">{{ serverText(card.error) }}</p>
    <div v-if="card.result" class="tool-card-result">
      <p class="tool-card-kicker">{{ $t("Ergebnis (Fremdinhalt)") }}</p>
      <pre data-testid="chat-tool-call-result">{{ card.result }}</pre>
    </div>
  </section>
</template>

<style scoped>
.tool-card { margin-top: 8px; padding: 12px 14px; border: 1px solid var(--ic-separator); border-radius: var(--ic-radius-panel); background: var(--ic-surface); display: grid; gap: 8px; min-width: 0; }
.tool-card.pending { border-color: var(--ic-status-wait, var(--ic-separator)); background: var(--ic-status-wait-bg, var(--ic-surface)); }
.tool-card-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px 12px; }
.tool-card-title { margin: 0; display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px; font: 500 14px/20px var(--ic-font); color: var(--ic-text); overflow-wrap: anywhere; }
.tool-card-title code { font: 500 13px/20px var(--ic-font-mono); }
.tool-card-source { color: var(--ic-text-2); font-weight: 400; }
.tool-card-kicker { margin: 0; color: var(--ic-text-2); font: 500 12px/18px var(--ic-font); }
.tool-card-pills { display: inline-flex; flex-wrap: wrap; gap: 6px; }
.tool-card-args { margin: 0; display: grid; grid-template-columns: minmax(0, max-content) minmax(0, 1fr); gap: 2px 12px; font: 400 13px/20px var(--ic-font); }
.tool-card-args dt { color: var(--ic-text-2); font-family: var(--ic-font-mono); overflow-wrap: anywhere; }
.tool-card-args dd { margin: 0; color: var(--ic-text); white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-card-note { margin: 0; color: var(--ic-text-2); font: 400 13px/20px var(--ic-font); overflow-wrap: anywhere; }
.tool-card-alert { margin: 0; color: var(--ic-status-error); font: 400 13px/20px var(--ic-font); }
.tool-card-confirm { display: grid; gap: 8px; }
.tool-card-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.tool-card-result pre { margin: 4px 0 0; max-height: 16rem; overflow: auto; padding: 8px 10px; border-radius: var(--ic-radius-small); background: var(--ic-surface-subtle); color: var(--ic-text); font: 400 12px/18px var(--ic-font-mono); white-space: pre-wrap; overflow-wrap: anywhere; }
@media (max-width: 500px) { .tool-card-args { grid-template-columns: minmax(0, 1fr); } }
</style>
