// Shared by the spec 002 browser tests: sign in on the real login page, open a deal, and set up
// data straight through the funder API.
import {expect, type Page} from '@playwright/test';

export const ISSUER = process.env.OIDC_ISSUER_URI ?? 'http://127.0.0.1:8090/funding';
export const FUNDER_API = process.env.FUNDER_API_URL ?? 'http://localhost:8080/api/v1';

/** A token without a browser, only to set up or read data straight through the funder API. */
export async function tokenFor(user: string): Promise<string> {
  const res = await fetch(`${ISSUER}/token`, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({grant_type: 'client_credentials', client_id: user, client_secret: 'unused', scope: 'funding-api'}),
  });
  expect(res.ok, `token for ${user}`).toBeTruthy();
  return (await res.json()).access_token as string;
}

export async function api(user: string, path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${FUNDER_API}${path}`, {...init, headers: {...init.headers, Authorization: `Bearer ${await tokenFor(user)}`}});
}

/**
 * LA-1006's latest version, unassigned: a supervisor's unassign through the funder API (AS-2.4),
 * so every run starts from a deal nobody has taken, without adding versions. Returns the overview.
 */
export async function unassignedLa1006(): Promise<Record<string, any>> {
  const overview = await (await api('supervisor-1', '/applications/LA-1006')).json();
  const res = await api('supervisor-1', `/versions/${overview.applicationVersionId}/assignee`, {
    method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({funderId: null}),
  });
  expect(res.ok, `unassign LA-1006: ${res.status} ${await res.clone().text()}`).toBeTruthy();
  return {...overview, assignedFunder: null};
}

/** Takes the deal straight through the funder API, as another user would from their own screen. */
export async function takeAs(user: string, versionId: number) {
  const res = await api(user, `/versions/${versionId}/take`, {method: 'POST'});
  expect(res.ok, `take as ${user}`).toBeTruthy();
}

/** AS-1.1: the real sign-in, on the provider's own login page. */
export async function signInAs(page: Page, user: string) {
  await page.goto('/');
  await page.getByRole('button', {name: 'Sign in'}).click();
  await page.locator('input[name="username"]').fill(user);          // mock-oauth2-server's login form
  await page.getByRole('button', {name: 'Sign-in'}).click();
  await expect(page.getByTestId('user')).toHaveText(user);
  expect(new URL(page.url()).pathname).toBe('/');                    // the callback cleaned the URL
}

export async function openDeal(page: Page, loanId: string) {
  await page.getByLabel('Loan id').fill(loanId);
  await page.getByRole('button', {name: 'Open', exact: true}).click();
}

export function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}
