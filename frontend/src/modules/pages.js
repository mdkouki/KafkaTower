import RegistryPage from './registry/RegistryPage.svelte';
import TopicsPage from './topics/TopicsPage.svelte';

// Maps a FrontendModule's `key` (backend: com.lynra.kafkatower.agents.core.specialist.FrontendModule,
// served at GET /api/ui/menu) to the Svelte component that renders that module's page.
// A module with a menu entry but no matching key here simply renders nothing — add its
// page component and register it here when it ships a frontend.
export const pages = {
  registry: RegistryPage,
  topics: TopicsPage
};
