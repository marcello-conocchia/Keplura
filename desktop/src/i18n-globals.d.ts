// Template helpers registered in main.ts (see src/i18n.ts).
export {};

declare module "vue" {
  interface ComponentCustomProperties {
    $t: typeof import("./i18n").tr;
    $dateLocale: typeof import("./i18n").dateLocale;
  }
}
