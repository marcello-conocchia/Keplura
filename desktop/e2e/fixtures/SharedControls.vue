<script setup lang="ts">
import { ref } from "vue";
import "@fontsource-variable/inter";
import "../../src/styles/theme.css";
import "../../src/styles/controls.css";
import Input from "../../src/components/ui/Input.vue";
import Button from "../../src/components/ui/Button.vue";
import StatusPill from "../../src/components/ui/StatusPill.vue";
import ConfirmDialog from "../../src/components/ui/ConfirmDialog.vue";

document.documentElement.dataset.icTheme = matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
const value=ref("Personalhandbuch"), error=ref(""), readonly=ref(false), disabled=ref(false), externalHint=ref("scope"), open=ref(false), busy=ref(false), clicks=ref(0);
</script>
<template>
<main style="max-width:560px;padding:24px;margin:auto">
    <h1>Gemeinsame Bedienelemente</h1>
    <p id="scope">Nur dieser Arbeitsbereich.</p><p id="extra">Zusätzlicher Hinweis.</p>
    <Input id="basis" label="Wissensbasis" v-model="value" hint="Ein Name, den dein Team leicht wiedererkennt."
      :error="error" :readonly="readonly" :disabled="disabled" :aria-describedby="externalHint" required maxlength="80" data-testid="field"/>
    <div style="display:flex;gap:8px;flex-wrap:wrap">
      <Button @click="error = error ? '' : 'Bitte gib einen Namen ein.'">Fehler wechseln</Button>
      <Button @click="externalHint = 'extra'">Hinweis wechseln</Button>
      <Button @click="readonly = !readonly">Nur lesen wechseln</Button>
      <Button @click="disabled = !disabled">Sperre wechseln</Button>
      <Button data-testid="open-confirm" @click="open = true">Bestätigung öffnen</Button>
      <Button :busy="busy" data-testid="busy-action" @click="clicks++">Speichern</Button>
    </div>
    <p data-testid="clicks">{{clicks}}</p>
    <div style="display:flex;gap:8px;flex-wrap:wrap"><StatusPill v-for="tone in ['ok','wait','error','neutral','info']" :tone="tone">{{tone}}</StatusPill></div>
    <ConfirmDialog :open="open" title="Wissensbasis entfernen?" message="Die Verknüpfung wird entfernt." :busy="busy" @close="open=false" @confirm="busy=true"/>
  </main>
</template>
