# Komponentenregister – vollständiger SFC-Abgleich

Stand `2de0a8a`, 15.11.2026. Alle 36 Vue-Dateien sind statisch erfasst. Direkte Importe sind keine Laufzeit-/Designabnahme. `main.ts` bindet `App.vue` ein. `Input.vue` verwendet das Label aus reka-ui; das lokale `ui/Label.vue` hat keinen direkten Vue-Import.

| Datei | Direkte Vue-Verwender | Erfasste Elementstellen |
|---|---|---|
| [desktop/src/App.vue](../../../desktop/src/App.vue) | — (siehe Einordnung oben) | 13 |
| [desktop/src/components/AdminPanel.vue](../../../desktop/src/components/AdminPanel.vue) | App.vue | 67 |
| [desktop/src/components/AppSidebar.vue](../../../desktop/src/components/AppSidebar.vue) | App.vue | 54 |
| [desktop/src/components/ChangePasswordView.vue](../../../desktop/src/components/ChangePasswordView.vue) | App.vue | 7 |
| [desktop/src/components/ChatView.vue](../../../desktop/src/components/ChatView.vue) | App.vue | 49 |
| [desktop/src/components/CommandPalette.vue](../../../desktop/src/components/CommandPalette.vue) | App.vue | 6 |
| [desktop/src/components/KnowledgePanel.vue](../../../desktop/src/components/KnowledgePanel.vue) | KnowledgeView.vue | 40 |
| [desktop/src/components/KnowledgeView.vue](../../../desktop/src/components/KnowledgeView.vue) | App.vue | 3 |
| [desktop/src/components/LoginView.vue](../../../desktop/src/components/LoginView.vue) | App.vue | 11 |
| [desktop/src/components/MembersPanel.vue](../../../desktop/src/components/MembersPanel.vue) | TeamView.vue | 29 |
| [desktop/src/components/OnboardingView.vue](../../../desktop/src/components/OnboardingView.vue) | App.vue | 10 |
| [desktop/src/components/SourcePreview.vue](../../../desktop/src/components/SourcePreview.vue) | ChatView.vue, KnowledgePanel.vue | 11 |
| [desktop/src/components/TeamView.vue](../../../desktop/src/components/TeamView.vue) | App.vue | 25 |
| [desktop/src/components/admin/BackupPanel.vue](../../../desktop/src/components/admin/BackupPanel.vue) | AdminPanel.vue | 2 |
| [desktop/src/components/admin/DataSourcesPanel.vue](../../../desktop/src/components/admin/DataSourcesPanel.vue) | AdminPanel.vue | 40 |
| [desktop/src/components/admin/DirectoryPanel.vue](../../../desktop/src/components/admin/DirectoryPanel.vue) | AdminPanel.vue | 31 |
| [desktop/src/components/admin/McpPanel.vue](../../../desktop/src/components/admin/McpPanel.vue) | AdminPanel.vue | 16 |
| [desktop/src/components/admin/QualityPanel.vue](../../../desktop/src/components/admin/QualityPanel.vue) | AdminPanel.vue | 21 |
| [desktop/src/components/admin/WebSearchPanel.vue](../../../desktop/src/components/admin/WebSearchPanel.vue) | AdminPanel.vue | 14 |
| [desktop/src/components/chat/ChatContextBar.vue](../../../desktop/src/components/chat/ChatContextBar.vue) | ChatView.vue | 16 |
| [desktop/src/components/chat/TemplateMenu.vue](../../../desktop/src/components/chat/TemplateMenu.vue) | ChatView.vue | 25 |
| [desktop/src/components/knowledge/DocumentTable.vue](../../../desktop/src/components/knowledge/DocumentTable.vue) | KnowledgePanel.vue | 12 |
| [desktop/src/components/knowledge/KbList.vue](../../../desktop/src/components/knowledge/KbList.vue) | KnowledgePanel.vue | 9 |
| [desktop/src/components/knowledge/StatusRing.vue](../../../desktop/src/components/knowledge/StatusRing.vue) | DocumentTable.vue | 0 |
| [desktop/src/components/layout/PageContainer.vue](../../../desktop/src/components/layout/PageContainer.vue) | AdminPanel.vue, KnowledgeView.vue, TeamView.vue | 0 |
| [desktop/src/components/layout/PageHeader.vue](../../../desktop/src/components/layout/PageHeader.vue) | AdminPanel.vue, KnowledgeView.vue, TeamView.vue | 0 |
| [desktop/src/components/ui/AppButton.vue](../../../desktop/src/components/ui/AppButton.vue) | AdminPanel.vue, MembersPanel.vue, TeamView.vue | 1 |
| [desktop/src/components/ui/AppDialog.vue](../../../desktop/src/components/ui/AppDialog.vue) | KnowledgePanel.vue, MembersPanel.vue, TeamView.vue, DataSourcesPanel.vue, DirectoryPanel.vue, QualityPanel.vue, TemplateMenu.vue, ConfirmDialog.vue | 4 |
| [desktop/src/components/ui/AppIcon.vue](../../../desktop/src/components/ui/AppIcon.vue) | App.vue, AdminPanel.vue, AppSidebar.vue, ChatView.vue, CommandPalette.vue, KnowledgePanel.vue, LoginView.vue, MembersPanel.vue, SourcePreview.vue, TeamView.vue, BackupPanel.vue, DataSourcesPanel.vue, DirectoryPanel.vue, QualityPanel.vue, ChatContextBar.vue, TemplateMenu.vue, DocumentTable.vue, KbList.vue, AppDialog.vue, Input.vue, TextField.vue | 0 |
| [desktop/src/components/ui/Button.vue](../../../desktop/src/components/ui/Button.vue) | ChangePasswordView.vue, ChatView.vue, KnowledgePanel.vue, LoginView.vue, MembersPanel.vue, OnboardingView.vue, SourcePreview.vue, TeamView.vue, DataSourcesPanel.vue, DirectoryPanel.vue, McpPanel.vue, QualityPanel.vue, WebSearchPanel.vue, TemplateMenu.vue, KbList.vue, ConfirmDialog.vue | 1 |
| [desktop/src/components/ui/ConfirmDialog.vue](../../../desktop/src/components/ui/ConfirmDialog.vue) | KnowledgePanel.vue, MembersPanel.vue, TeamView.vue, DataSourcesPanel.vue, DirectoryPanel.vue | 3 |
| [desktop/src/components/ui/Input.vue](../../../desktop/src/components/ui/Input.vue) | LoginView.vue | 3 |
| [desktop/src/components/ui/Label.vue](../../../desktop/src/components/ui/Label.vue) | — (siehe Einordnung oben) | 1 |
| [desktop/src/components/ui/MarkdownAnswer.vue](../../../desktop/src/components/ui/MarkdownAnswer.vue) | ChatView.vue | 0 |
| [desktop/src/components/ui/StatusPill.vue](../../../desktop/src/components/ui/StatusPill.vue) | AdminPanel.vue, ChatView.vue, MembersPanel.vue, TeamView.vue | 1 |
| [desktop/src/components/ui/TextField.vue](../../../desktop/src/components/ui/TextField.vue) | AdminPanel.vue, KnowledgePanel.vue, MembersPanel.vue, TeamView.vue | 2 |

Die Elementstellen enthalten native Controls, Elemente mit Rollen und Komponenteninstanzen einschließlich Icons/Portals. 527 ist deshalb **keine** Zahl unabhängiger Screens oder Schaltflächen. Wiederholte Datensätze erzeugen zur Laufzeit weitere Instanzen. Alle 314 v-if/v-else/v-show/v-for-Stellen stehen mit Ausdruck im JSON. Dynamisch erzeugte Markdown-Kopieraktionen und Betriebssystemdialoge sind zusätzlich im Fachinventar erfasst.
