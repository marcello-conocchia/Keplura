<script setup lang="ts">
import { nextTick, onMounted, onUnmounted, ref, watch } from "vue";
import AppIcon from "./AppIcon.vue";
import Button from "./Button.vue";
const props=withDefaults(defineProps<{
  open:boolean; title:string; closeLabel?:string; error?:string;
  size?:"sm"|"md"|"lg"|"wizard"; busy?:boolean; dirty?:boolean; initialFocus?:"field"|"title"|"cancel";
}>(),{closeLabel:"Schließen",error:"",size:"sm",busy:false,dirty:false,initialFocus:"field"});
const emit=defineEmits<{close:[]}>();
const dlg=ref<HTMLDialogElement|null>(null), titleEl=ref<HTMLElement|null>(null);
const discard=ref(false), busyNotice=ref(false);
let opener:HTMLElement|null=null, editingFocus:HTMLElement|null=null, closing=false;
async function focusContent(){
  await nextTick();
  if(props.initialFocus==="title") titleEl.value?.focus();
  else if(props.initialFocus==="cancel") (dlg.value?.querySelector('[data-testid="confirm-cancel"]') as HTMLElement|null)?.focus();
  else (dlg.value?.querySelector("input:not([disabled]), textarea:not([disabled]), select:not([disabled])") as HTMLElement|null)?.focus();
}
async function syncOpen(want:boolean){
  const d=dlg.value;if(!d)return;
  if(want&&!d.open){opener=document.activeElement instanceof HTMLElement?document.activeElement:null;discard.value=false;busyNotice.value=false;closing=false;d.showModal();await focusContent();}
  else if(!want&&d.open){closing=true;d.close();if(opener?.isConnected)opener.focus();}
}
async function keepEditing(){discard.value=false;await nextTick();if(editingFocus?.isConnected)editingFocus.focus();else await focusContent();}
async function requestClose(){
  if(props.busy){busyNotice.value=true;return;}
  if(discard.value){await keepEditing();return;}
  if(props.dirty){editingFocus=document.activeElement instanceof HTMLElement?document.activeElement:null;discard.value=true;await nextTick();(dlg.value?.querySelector('[data-testid="dialog-keep-editing"]') as HTMLElement|null)?.focus();return;}
  emit("close");
}
defineExpose({ requestClose });
// Chromium schließt ein modales dialog nach wiederholtem Escape ohne Nutzeraktivierung
// direkt (close watcher), also ohne cancel. Dirty/Busy dürfen dadurch nicht umgangen werden.
async function onNativeClose(){
  if(closing){closing=false;return;}
  if(!props.open)return;
  if(!props.busy&&!props.dirty){emit("close");return;}
  dlg.value?.showModal();
  if(props.busy){busyNotice.value=true;return;}
  discard.value=true;await nextTick();
  (dlg.value?.querySelector('[data-testid="dialog-keep-editing"]') as HTMLElement|null)?.focus();
}
const TABBABLE='button:not([disabled]),[href],input:not([disabled]):not([type="hidden"]),select:not([disabled]),textarea:not([disabled]),summary,[tabindex]:not([tabindex="-1"])';
function tabbable(root:HTMLElement){
  return [...root.querySelectorAll<HTMLElement>(TABBABLE)].filter(e=>{
    if(e.getClientRects().length===0)return false;
    if(e instanceof HTMLInputElement&&e.type==="radio"&&e.name&&!e.checked)
      return !root.querySelector(`input[type="radio"][name="${e.name}"]:checked`);
    return true;
  });
}
// Genau eine aktive Fokusfalle: Tab/Shift+Tab laufen am Rand im Dialog um statt in den Fensterrahmen.
function onKeydown(event:KeyboardEvent){
  const d=dlg.value;if(event.key!=="Tab"||!d)return;
  const items=tabbable(d);
  if(!items.length){event.preventDefault();return;}
  const first=items[0],last=items[items.length-1],a=document.activeElement;
  const inside=a instanceof HTMLElement&&d.contains(a)&&a!==d;
  if(event.shiftKey&&(a===first||!inside||a===titleEl.value)){event.preventDefault();last.focus();}
  else if(!event.shiftKey&&(a===last||!inside)){event.preventDefault();first.focus();}
}
function backdrop(event:MouseEvent){if(event.target===dlg.value)void requestClose();}
watch(()=>props.open,want=>void syncOpen(want));
watch(()=>props.error,async value=>{ if(value){await nextTick();(dlg.value?.querySelector('[data-testid="dialog-error"]') as HTMLElement|null)?.focus();} });
watch(()=>props.busy,value=>{if(!value)busyNotice.value=false;});
onMounted(()=>void syncOpen(props.open));
onUnmounted(()=>{closing=true;if(dlg.value?.open)dlg.value.close();if(opener?.isConnected)opener.focus();});
</script>
<template>
  <dialog ref="dlg" class="ic-dialog" :class="['dialog-'+size,{'dialog-discarding':discard}]" aria-modal="true" :aria-label="discard?'Änderungen verwerfen?':title"
    @cancel.prevent="requestClose" @close="onNativeClose" @click="backdrop" @keydown="onKeydown">
    <div class="box">
      <header class="head"><h2 ref="titleEl" tabindex="-1" class="ttl">{{discard?'Änderungen verwerfen?':title}}</h2>
        <button type="button" class="x" data-testid="dialog-close" :aria-label="closeLabel" :title="closeLabel" :aria-disabled="busy||undefined" @click="requestClose"><AppIcon name="close" :size="20"/></button>
      </header>
      <div v-if="discard" class="discard-view">
        <p>Deine noch nicht gespeicherten Änderungen gehen verloren. Bereits gespeicherte Änderungen bleiben erhalten.</p>
        <div class="discard-actions"><Button data-testid="dialog-keep-editing" @click="keepEditing">Weiter bearbeiten</Button><Button variant="danger" data-testid="dialog-discard" @click="emit('close')">Änderungen verwerfen</Button></div>
      </div>
      <div v-show="!discard" class="dialog-content"><div class="inner"><slot/></div>
        <p v-if="error" class="dlg-error" tabindex="-1" role="alert" data-testid="dialog-error">{{error}}</p>
        <footer v-if="$slots.footer" class="dialog-footer"><slot name="footer"/></footer>
      </div>
      <p v-if="busyNotice" role="status" class="busy-notice">Der Vorgang läuft. Bitte warte, bis er abgeschlossen ist.</p>
    </div>
  </dialog>
</template>
<style scoped>
.ic-dialog{--dialog-width:var(--ic-dialog-sm);width:min(var(--dialog-width),calc(100vw - 32px));max-width:none;max-height:calc(100dvh - 32px);padding:0;border:1px solid var(--ic-separator);border-radius:var(--ic-radius-dialog);background:var(--ic-surface);color:var(--ic-text);box-shadow:var(--ic-shadow-panel);overflow:auto}
.ic-dialog.dialog-discarding{--dialog-width:var(--ic-dialog-sm)}
.dialog-md{--dialog-width:var(--ic-dialog-md)}.dialog-lg{--dialog-width:var(--ic-dialog-lg)}.dialog-wizard{--dialog-width:var(--ic-dialog-wizard)}
.ic-dialog::backdrop{background:var(--ic-scrim)}.box,.dialog-content{min-height:0;display:flex;flex-direction:column}
.head{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:20px 24px;border-bottom:1px solid var(--ic-separator)}
.ttl{margin:0;font-size:20px;line-height:28px;font-weight:600;overflow-wrap:anywhere}
.x{display:inline-flex;align-items:center;justify-content:center;flex:none;width:40px;height:40px;border:0;border-radius:6px;color:var(--ic-text-2);background:transparent;cursor:pointer}.x:hover{background:var(--ic-hover-tint)}
.inner{padding:24px;min-height:0}.dialog-footer{padding:16px 24px;border-top:1px solid var(--ic-separator);background:var(--ic-surface)}
.dlg-error,.busy-notice{margin:0;padding:16px 24px;font-size:14px;line-height:20px;color:var(--ic-status-error);background:var(--ic-status-error-bg)}.busy-notice{color:var(--ic-text-2);background:var(--ic-surface-subtle)}
.discard-view{padding:24px}.discard-view p{margin:0 0 24px;line-height:24px}.discard-actions{display:flex;gap:8px;justify-content:flex-end;flex-wrap:wrap}
.dialog-wizard .head{border-bottom:0}.dialog-wizard .dialog-footer{border-top:0}
.dialog-wizard .box{max-height:calc(100dvh - 34px)}.dialog-wizard .dialog-content{overflow:hidden}.dialog-wizard .inner{overflow:auto;padding:0}.dialog-wizard .dialog-footer{flex:none}
@media(max-width:767px){.head,.inner,.discard-view{padding:16px}.dialog-footer{padding:12px 16px}.dialog-wizard{width:100vw;height:100dvh;max-height:100dvh;margin:0;border:0;border-radius:0}.dialog-wizard .box{height:100%;max-height:100%}.x{width:44px;height:44px}}
@media(max-height:600px){.dialog-wizard .box{max-height:none;height:auto;min-height:100%}.dialog-wizard .dialog-content,.dialog-wizard .inner{overflow:visible}.head{padding-top:12px;padding-bottom:12px}}
</style>
