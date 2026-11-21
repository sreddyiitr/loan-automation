// The signed-in user's OIDC access token, kept in memory only (plan §4: never localStorage).
// T105 fills it from the Authorization Code + PKCE sign-in. Until then, two stand-ins:
//  - the Playwright test sets window.__FUNDER_TOKEN__ to a token from the mock provider;
//  - in the Vite dev server only, opening the page with #access_token=<token> takes the token into
//    memory and clears it from the address bar (the URL fragment never reaches a server).
declare global {
  interface Window {
    __FUNDER_TOKEN__?: string;
  }
}

if (import.meta.env.DEV && window.location.hash.startsWith('#access_token=')) {
  window.__FUNDER_TOKEN__ = decodeURIComponent(window.location.hash.slice('#access_token='.length));
  history.replaceState(null, '', window.location.pathname + window.location.search);
}

export function accessToken(): string | undefined {
  return window.__FUNDER_TOKEN__;
}
