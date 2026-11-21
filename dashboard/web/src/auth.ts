// Sign-in (spec 002 AS-1.1, FR-09; plan §4): OIDC Authorization Code with PKCE against the local
// mock provider, through oidc-client-ts. The browser is a public client, `funder-dashboard`.
//
// - The access token is kept in memory only (InMemoryWebStorage, never localStorage), so a page
//   reload means signing in again. That's a quick step with the mock provider.
// - oidc-client-ts keeps only the short-lived PKCE verifier and state in sessionStorage, for the
//   round trip to the login page. They're removed once the callback is handled.
import {InMemoryWebStorage, UserManager, WebStorageStateStore, type User} from 'oidc-client-ts';

const ISSUER = import.meta.env.VITE_OIDC_ISSUER ?? 'http://127.0.0.1:8090/funding';
export const CALLBACK_PATH = '/callback';

const manager = new UserManager({
  authority: ISSUER,
  client_id: 'funder-dashboard',
  redirect_uri: `${window.location.origin}${CALLBACK_PATH}`,
  response_type: 'code',
  scope: 'openid',
  userStore: new WebStorageStateStore({store: new InMemoryWebStorage()}),
  automaticSilentRenew: false,
});

let current: User | null = null;

/** Sends the browser to the provider's login page. */
export function signIn(): Promise<void> {
  return manager.signinRedirect();
}

let completing: Promise<User> | undefined;

/**
 * On /callback: swaps the code (with the PKCE verifier) for tokens, then cleans the URL. Runs once
 * per page load: a code can be exchanged only once, and React's StrictMode calls effects twice.
 */
export function completeSignIn(): Promise<User> {
  completing ??= manager.signinRedirectCallback().then((user) => {
    current = user;
    window.history.replaceState(null, '', '/');
    return user;
  });
  return completing;
}

export async function signOut(): Promise<void> {
  current = null;
  await manager.removeUser();
}

/** The access token the agent relays to the funder API (FR-11), if signed in and not expired. */
export function accessToken(): string | undefined {
  return current && !current.expired ? current.access_token : undefined;
}

/** The signed-in user's id: the token's sub (the funder API decides the role, D-13). */
export function userId(): string | undefined {
  return current?.profile.sub;
}
