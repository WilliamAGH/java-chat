import { mount } from "svelte";
import App from "./App.svelte";
import "./styles/global.css";

const RELOAD_GUARD_STORAGE_KEY = "vite-preload-error-reloaded";

// During a rolling deploy the old container can 404 lazily-loaded chunks that
// the new build's index.html references (Vite docs: vite:preloadError). One
// guarded reload fetches the fresh index.html instead of leaving a dead page;
// the sessionStorage guard prevents a reload loop when the failure persists.
window.addEventListener("vite:preloadError", (preloadErrorEvent) => {
  if (sessionStorage.getItem(RELOAD_GUARD_STORAGE_KEY) === null) {
    preloadErrorEvent.preventDefault();
    sessionStorage.setItem(RELOAD_GUARD_STORAGE_KEY, "true");
    window.location.reload();
  }
});

const app = mount(App, {
  target: document.getElementById("app")!,
});

export default app;
