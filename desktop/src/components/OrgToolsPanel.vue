<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useAuthStore } from "../auth-store";
import { getOrgToolSettings, updateOrgToolSettings, type OrgToolSettings } from "../productivity-api";
import { tr } from "../i18n";
const auth=useAuthStore();
const isOwner=computed(()=>auth.orgs.find(o=>o.id===auth.selectedOrgId)?.role==="OWNER");
const orgTools=ref<OrgToolSettings|null>(null);
const orgToolsError=ref("");
const orgToolsBusy=ref(false);
async function loadOrgTools() {
  const orgId=auth.selectedOrgId; orgTools.value=null; orgToolsError.value="";
  if(orgId===null || !isOwner.value) return;
  const result=await getOrgToolSettings(orgId);
  if(auth.selectedOrgId!==orgId) return;
  if(result.kind==="ok") orgTools.value=result.value;
  else orgToolsError.value=tr("Werkzeugeinstellungen benötigen die Organisationsverwaltung.");
}
async function setOrgTools(input:{knowledgeEnabled?:boolean;serverId?:number;internal?:boolean}) {
  const orgId=auth.selectedOrgId;
  if(orgId===null || orgToolsBusy.value) return;
  orgToolsBusy.value=true;
  try {
    const result=await updateOrgToolSettings(orgId,input);
    if(auth.selectedOrgId===orgId) {
      if(result.kind==="ok") { orgTools.value=result.value; orgToolsError.value=""; }
      else orgToolsError.value=result.kind==="error"?result.message:tr("Deine Sitzung ist abgelaufen.");
    }
  } finally { orgToolsBusy.value=false; }
}
watch(()=>[auth.selectedOrgId,isOwner.value],loadOrgTools,{immediate:true});
</script>
<template>
  <section v-if="isOwner" class="connection-note" data-testid="org-tool-settings">
    <label v-if="orgTools" class="connection-check"><input type="checkbox" :checked="orgTools.knowledgeEnabled" :disabled="orgToolsBusy" @change="setOrgTools({knowledgeEnabled:!orgTools.knowledgeEnabled})" />{{ $t("Wissenssuche und Dokumentstellen als Werkzeuge für diese Organisation freigeben") }}</label>
    <label v-for="server in orgTools?.servers??[]" :key="server.serverId" class="connection-check">
      <input type="checkbox" :checked="server.internal" :disabled="orgToolsBusy" @change="setOrgTools({serverId:server.serverId,internal:!server.internal})" />
      {{ server.name }}: {{ $t("Internes Ziel für diese Organisation – Daten bleiben im Haus") }}
    </label>
    <p v-if="orgToolsError" role="alert">{{orgToolsError}}</p>
  </section>
</template>
