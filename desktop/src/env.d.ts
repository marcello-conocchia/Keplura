/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Base URL of the Keplura Server API, e.g. http://localhost:8080 */
  readonly VITE_SERVER_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}

declare module "*.vue" {
  import type { DefineComponent } from "vue";
  const component: DefineComponent<{}, {}, any>;
  export default component;
}
