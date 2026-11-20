<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { getWebSearchAdmin, putWebSearchAdmin, testWebSearchAdmin, type WebSearchAdmin } from "../../productivity-api";
import Button from "../ui/Button.vue";
import Input from "../ui/Input.vue";
import StatusPill from "../ui/StatusPill.vue";
const state=ref<WebSearchAdmin|null>(null), provider=ref<WebSearchAdmin["provider"]>("none");
const url=ref(""),apiKey=ref(""),removeKey=ref(false),results=ref(5),blocked=ref("");
const message=ref(""),bad=ref(false),loading=ref(true),loadError=ref("");
const busy=ref<"save"|"test"|null>(null),testHits=ref<{title:string;url:string}[]>([]);
const dirty=computed(()=>!!state.value&&(provider.value!==state.value.provider||url.value!==state.value.url||results.value!==state.value.results||blocked.value!==state.value.blockedDomains||!!apiKey.value||removeKey.value));
function apply(v:WebSearchAdmin){state.value=v;provider.value=v.provider;url.value=v.url;results.value=v.results;blocked.value=v.blockedDomains;apiKey.value="";removeKey.value=false;}
async function load(){loading.value=true;loadError.value="";const r=await getWebSearchAdmin();loading.value=false;if(r.kind==="ok")apply(r.value);else loadError.value=r.kind==="error"?r.message:"Deine Sitzung ist abgelaufen.";}
onMounted(load);
function say(text:string,isBad=false){message.value=text;bad.value=isBad;}
async function save(){
  if(busy.value)return;busy.value="save";message.value="";testHits.value=[];
  try{const r=await putWebSearchAdmin({provider:provider.value,url:url.value,results:results.value,blockedDomains:blocked.value,apiKey:removeKey.value?"":apiKey.value.trim()||null});
    if(r.kind==="ok"){apply(r.value);say(r.value.enabled?"Einstellungen gespeichert. Die Websuche ist aktiv.":"Einstellungen gespeichert. Die Websuche ist ausgeschaltet.");}
    else say(r.kind==="error"?r.message:"Deine Sitzung ist abgelaufen.",true);
  }finally{busy.value=null;}
}
async function test(){
  if(busy.value||dirty.value)return;busy.value="test";message.value="";testHits.value=[];
  try{const r=await testWebSearchAdmin();if(r.kind==="ok"){say(r.value.message,!r.value.ok);testHits.value=r.value.results;}else say(r.kind==="error"?r.message:"Deine Sitzung ist abgelaufen.",true);}finally{busy.value=null;}
}
</script>
<template>
  <section class="connection-page" data-testid="admin-websearch-section">
    <header class="connection-header"><div class="connection-heading"><h2>Websuche</h2><p class="connection-description">Ergänze Antworten im Chat bei Bedarf um Informationen aus dem Internet.</p></div><StatusPill v-if="state" :tone="state.enabled?'info':'neutral'" data-testid="websearch-status">{{state.enabled?'Aktiv':'Ausgeschaltet'}}</StatusPill></header>
    <p v-if="loading" class="connection-loading" role="status">Einstellungen werden geladen …</p>
    <div v-if="loadError" class="connection-note error connection-error" role="alert"><p>{{loadError}}</p><Button size="compact" @click="load">Erneut versuchen</Button></div>
    <template v-if="state && !loading">
      <p v-if="state.source==='env'" class="connection-note">Derzeit gelten die Einstellungen des Servers. Mit „Speichern“ übernimmst du sie in die Administration.</p>
      <form class="connection-form" @submit.prevent="save">
        <section class="connection-section"><h3>Suchdienst</h3>
          <label class="connection-field">Anbieter<select v-model="provider" class="ic-native-control" :disabled="!!busy" data-testid="admin-websearch-provider"><option value="none">Ausgeschaltet</option><option value="searxng">SearXNG · eigener Suchdienst</option><option value="brave">Brave Search API</option><option value="tavily">Tavily</option></select></label>
          <Input v-if="provider==='searxng'" id="websearch-url" v-model="url" label="Adresse des Suchdienstes" type="url" required placeholder="https://suche.firma.local" hint="Die JSON-Ausgabe muss beim Suchdienst freigeschaltet sein." :disabled="!!busy" data-testid="admin-websearch-url" />
          <template v-if="provider==='brave'||provider==='tavily'">
            <Input id="websearch-key" v-model="apiKey" label="API-Schlüssel" type="password" autocomplete="off" :disabled="!!busy||removeKey" :placeholder="state.hasApiKey?'Gespeichert – nur zum Ändern eingeben':'Schlüssel eingeben'" :hint="state.hasApiKey?'Leer lassen, um den gespeicherten Schlüssel zu behalten.':undefined" data-testid="admin-websearch-key" />
            <p v-if="!state.secretStorageAvailable" class="connection-note error">Die sichere Schlüsselablage ist noch nicht eingerichtet. Bitte wende dich an den Betrieb des Servers.</p>
            <label v-if="state.hasApiKey" class="connection-check"><input v-model="removeKey" :disabled="!!busy" type="checkbox" data-testid="websearch-remove-key" />Gespeicherten Schlüssel beim Speichern entfernen</label>
          </template>
          <p v-if="provider==='none'" class="connection-hint">Die Websuche wird nach dem Speichern ausgeschaltet. Der Chat und die Arbeit mit eigenen Dokumenten bleiben verfügbar.</p>
        </section>
        <section v-if="provider!=='none'" class="connection-section"><h3>Suchergebnisse</h3>
          <label class="connection-field">Treffer pro Frage<input v-model.number="results" class="ic-native-control" type="number" min="1" max="10" required :disabled="!!busy" data-testid="websearch-results" /><small>Zwischen 1 und 10 Ergebnisse.</small></label>
          <label class="connection-field">Gesperrte Domains<textarea v-model="blocked" class="ic-native-control" rows="3" placeholder="beispiel.de, werbung.com" :disabled="!!busy" data-testid="websearch-blocked" /><small>Mehrere Domains durch Kommas oder neue Zeilen trennen.</small></label>
        </section>
        <p v-if="dirty" class="connection-hint" data-testid="websearch-unsaved">Ungespeicherte Änderungen. Der Verbindungstest verwendet die gespeicherten Einstellungen.</p>
        <div class="connection-submit"><Button :disabled="!!busy||!state.enabled||dirty" :busy="busy==='test'" :title="dirty?'Speichere zuerst deine Änderungen':'Gespeicherte Verbindung testen'" data-testid="admin-websearch-test" @click="test">{{busy==='test'?'Verbindung wird geprüft …':'Verbindung testen'}}</Button><Button variant="primary" type="submit" :disabled="!!busy" :busy="busy==='save'" data-testid="admin-websearch-save">{{busy==='save'?'Wird gespeichert …':'Speichern'}}</Button></div>
      </form>
      <p v-if="message" class="connection-note" :class="bad?'error':'ok'" :role="bad?'alert':'status'" data-testid="websearch-message">{{message}}</p>
      <section v-if="testHits.length" class="connection-section"><h3>Ergebnisse des Verbindungstests</h3><ul class="connection-results"><li v-for="h in testHits" :key="h.url"><strong>{{h.title}}</strong><small>{{h.url}}</small></li></ul></section>
      <details class="connection-details"><summary>Wie werden Internetquellen verwendet?</summary><p>Der Chat nutzt Titel, Link und Auszug der Suchtreffer. Keplura ruft die verlinkten Seiten nicht selbst ab. Mit einem eigenen SearXNG-Dienst steuerst du, über welchen Suchdienst die Anfragen laufen.</p></details>
    </template>
  </section>
</template>
