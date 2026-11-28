export type AppView = "chat" | "wissen" | "team" | "admin" | "verbindungen";

/** TU-06b: lets nested views (e.g. the chat tool menu) open another view without prop drilling. */
export const NAVIGATE_KEY = Symbol("keplura.navigate");
