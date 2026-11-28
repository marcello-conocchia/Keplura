<script setup lang="ts">
/**
 * TU-02 — one tool call card under a chat answer: tool/source, risk class, readable arguments,
 * honest status and the result (foreign content, plain text) or a generic error. A pending
 * action shows Confirm / Reject; the server binds and executes exactly the shown call.
 */
import { computed, ref } from "vue";
import Button from "../ui/Button.vue";
import StatusPill from "../ui/StatusPill.vue";
import ToolArtifacts from "./ToolArtifacts.vue";
import { tr, serverText, dateLocale } from "../../i18n";
import {
  argumentEntries, canDecide, hiddenArgumentCount, riskLabel, statusMeta, summaryIsPartial, type ToolCallCard,
} from "../../tool-cards";

const props = defineProps<{ card: ToolCallCard; busy: boolean; error?: string | null; orgId?: number | null; conversationId?: number | null }>();
const emit = defineEmits<{ decide: [decision: "confirm" | "reject", answer?: string] }>();

const meta = computed(() => statusMeta(props.card.status));
const args = computed(() => argumentEntries(props.card.argumentFields));
const hidden = computed(() => hiddenArgumentCount(props.card.argumentFields));
const partial = computed(() => summaryIsPartial(props.card.argumentFields));
const decidable = computed(() => canDecide(props.card));
const headingId = computed(() => `tool-call-${props.card.id}`);
/** TU-05d: a question to the person is answered, not executed. */
const isQuestion = computed(() => props.card.kind === "QUESTION");
const freeAnswer = ref("");
const MAX_ANSWER = 1000;
function sendFree() {
  const text = freeAnswer.value.trim();
  if (text && text.length <= MAX_ANSWER) emit("decide", "confirm", text);
}
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
        <span class="tool-card-kicker">{{ isQuestion ? $t("Rückfrage") : $t("Werkzeug") }}</span>
        <code v-if="!isQuestion">{{ card.tool }}</code>
        <span v-if="!isQuestion" class="tool-card-source">· {{ card.source }}</span>
      </h4>
      <span v-if="isQuestion" class="tool-card-pills">
        <StatusPill :tone="meta.tone" data-testid="chat-tool-call-status">{{ card.status === "SUCCEEDED" ? $t("Beantwortet") : card.status === "REJECTED" ? $t("Nicht beantwortet") : $t(meta.label) }}</StatusPill>
      </span>
      <span v-else class="tool-card-pills">
        <StatusPill :tone="card.risk === 'CRITICAL' ? 'error' : card.risk === 'WRITE' ? 'wait' : 'neutral'" data-testid="chat-tool-call-risk">{{ $t(riskLabel(card.risk)) }}</StatusPill>
        <StatusPill :tone="meta.tone" data-testid="chat-tool-call-status">{{ $t(meta.label) }}</StatusPill>
      </span>
    </header>

    <template v-if="isQuestion">
      <p v-if="card.question" class="tool-card-question" data-testid="chat-tool-question">{{ card.question.text }}</p>
      <p v-else class="tool-card-note">{{ $t("Die Rückfrage wird nicht mehr angezeigt.") }}</p>
      <div v-if="decidable && card.question" class="tool-card-confirm" role="group" :aria-label="$t('Rückfrage beantworten')">
        <div v-if="card.question.options.length" class="tool-card-actions">
          <Button v-for="(option, i) in card.question.options" :key="i" size="compact" :busy="busy" :disabled="busy"
            :data-testid="`chat-tool-question-option-${i}`" @click="emit('decide', 'confirm', option)">{{ option }}</Button>
        </div>
        <form v-if="card.question.freeText" class="tool-card-free" @submit.prevent="sendFree">
          <textarea v-model="freeAnswer" class="ic-native-control" rows="2" :maxlength="MAX_ANSWER" :disabled="busy"
            :aria-label="$t('Eigene Antwort')" :placeholder="$t('Eigene Antwort')" data-testid="chat-tool-question-free"></textarea>
          <Button size="compact" type="submit" :busy="busy" :disabled="busy || !freeAnswer.trim()" data-testid="chat-tool-question-send">{{ $t("Antwort senden") }}</Button>
        </form>
        <div class="tool-card-actions">
          <Button variant="ghost" size="compact" :disabled="busy" data-testid="chat-tool-question-skip" @click="emit('decide', 'reject')">{{ $t("Nicht beantworten") }}</Button>
        </div>
        <p class="tool-card-note">{{ $t("Die Antwort setzt die Frage fort, bis {0}.", deadline) }}</p>
      </div>
      <div v-if="card.status === 'SUCCEEDED' && card.result" class="tool-card-result">
        <p class="tool-card-kicker">{{ $t("Deine Antwort") }}</p>
        <pre data-testid="chat-tool-question-answer">{{ card.result }}</pre>
      </div>
      <p v-if="error" class="tool-card-alert" role="alert">{{ error }}</p>
      <p v-if="card.error" class="tool-card-note" data-testid="chat-tool-call-error">{{ serverText(card.error) }}</p>
    </template>
    <template v-else>
    <p v-if="card.mayContainInternal" class="tool-card-note" data-testid="chat-tool-call-disclosure">{{ $t("Enthält möglicherweise interne Inhalte.") }}</p>

    <p v-if="card.parentCallId" class="tool-card-note" data-testid="chat-tool-call-parent">
      <a :href="`#tool-call-${card.parentCallId}`">{{ $t("Folgeaktion zu {0}", card.parentTool ?? card.parentCallId) }}</a>
    </p>

    <dl v-if="args.length" class="tool-card-args">
      <template v-for="a in args" :key="a.key">
        <dt>{{ a.key }}</dt>
        <dd>{{ a.value }}</dd>
      </template>
    </dl>
    <p v-else-if="card.argumentsText !== null" class="tool-card-note">{{ $t("Ohne Eingaben.") }}</p>
    <p v-if="card.argumentsText === null && !card.sourceUnavailable" class="tool-card-note">{{ $t("Die Eingaben wurden nicht gespeichert (zu groß oder ungültig).") }}</p>
    <p v-if="card.sourceTemporarilyUnavailable" class="tool-card-note">{{ $t("Die internen Fundstellen sind vorübergehend nicht verfügbar. Bitte erneut laden.") }}</p>
    <p v-else-if="card.sourceUnavailable" class="tool-card-note">{{ $t("Die internen Fundstellen sind nicht mehr lesbar.") }}</p>
    <p v-if="hidden > 0" class="tool-card-note">{{ $t("{0} weitere Eingaben nicht in der Übersicht.", hidden) }}</p>
    <p v-if="partial" class="tool-card-note">{{ $t("Die Übersicht ist gekürzt. Maßgeblich sind die vollständigen Eingaben.") }}</p>

    <!-- Exact stored text that runs on confirmation: always visible while a decision is open. -->
    <div v-if="card.argumentsText !== null && decidable" class="tool-card-full">
      <p class="tool-card-kicker">{{ $t("Vollständige Eingaben – genau so wird ausgeführt") }}</p>
      <pre data-testid="chat-tool-call-arguments">{{ card.argumentsText }}</pre>
    </div>
    <details v-else-if="card.argumentsText !== null && card.argumentsText !== '{}'" class="tool-card-full">
      <summary class="tool-card-kicker">{{ $t("Vollständige Eingaben anzeigen") }}</summary>
      <pre data-testid="chat-tool-call-arguments">{{ card.argumentsText }}</pre>
    </details>

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
    <ToolArtifacts v-if="card.artifacts?.length" :artifacts="card.artifacts" :org-id="orgId ?? null" :conversation-id="conversationId ?? null" />
    </template>
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
.tool-card-full pre { margin: 4px 0 0; max-height: 20rem; overflow: auto; padding: 8px 10px; border-radius: var(--ic-radius-small); background: var(--ic-surface-subtle); color: var(--ic-text); font: 400 12px/18px var(--ic-font-mono); white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-card-full summary { cursor: pointer; }
.tool-card-result pre { margin: 4px 0 0; max-height: 16rem; overflow: auto; padding: 8px 10px; border-radius: var(--ic-radius-small); background: var(--ic-surface-subtle); color: var(--ic-text); font: 400 12px/18px var(--ic-font-mono); white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-card-question { margin: 0; color: var(--ic-text); font: 500 14px/20px var(--ic-font); white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-card-free { display: grid; gap: 6px; }
.tool-card-free textarea { width: 100%; resize: vertical; font: 400 13px/20px var(--ic-font); }
@media (max-width: 500px) { .tool-card-args { grid-template-columns: minmax(0, 1fr); } }
</style>
