<script setup lang="ts">
/** REDESIGN: Bereich „Wissen" — Seitenkopf + Explorer (KnowledgePanel). */
import { onMounted } from "vue";
import { useAuthStore } from "../auth-store";
import KnowledgePanel from "./KnowledgePanel.vue";
import PageContainer from "./layout/PageContainer.vue";
import PageHeader from "./layout/PageHeader.vue";

const auth = useAuthStore();
onMounted(() => {
  if (auth.orgs.length === 0 && !auth.orgsError) {
    void auth.loadOrgs();
  }
});
</script>

<template>
  <PageContainer>
    <template #header>
      <PageHeader :title="$t('Wissen')">
        <template #description>
          <span data-testid="session-user-line">{{ auth.selectedSpace?.name ?? "" }}</span>
          <span class="sr-only" data-testid="session-user">{{ auth.user?.displayName || $t('Keplura-Nutzer') }}</span>
        </template>
      </PageHeader>
    </template>
    <div data-testid="session-card">
      <KnowledgePanel />
    </div>
  </PageContainer>
</template>

<style scoped>
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
</style>
