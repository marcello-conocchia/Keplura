<script setup lang="ts">
import { computed } from "vue";
import { sourceFreshness } from "../../source-freshness";
const props = defineProps<{ value?: string | null }>();
const stamp = computed(() => sourceFreshness(props.value));
</script>
<template>
  <span class="source-freshness" data-testid="source-freshness">
    <span>{{ $t("Stand:") }} <time v-if="stamp.date" :datetime="stamp.date">{{ stamp.label }}</time><template v-else>{{ stamp.label }}</template></span>
    <span v-if="stamp.older" class="source-aged" :title="$t('Stand vom {0}, bitte prüfen, ob es aktuell ist', stamp.label)" data-testid="source-aged"><span aria-hidden="true">·</span> {{ $t("älteres Dokument") }}</span>
  </span>
</template>
<style scoped>
.source-freshness { display:inline-flex;align-items:center;gap:8px;flex-wrap:wrap;color:var(--ic-text-2);font:400 12px/18px var(--ic-font); }
.source-aged { color:var(--ic-text-2); }
</style>
