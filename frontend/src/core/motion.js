// Svelte transitions don't read the prefers-reduced-motion media query on their own
// (unlike plain CSS transitions driven by the --duration-* tokens in app.css), so
// every component using fade/fly/scale should build its params through here.
const prefersReducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

export function motionParams(params) {
  return prefersReducedMotion() ? { ...params, duration: 0 } : params;
}
