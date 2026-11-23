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

/** D1c: a fact on the overview is a label/value pair in the FactList; this is its value. */
export function fact(page: Page, label: string) {
  return page.locator(`.fact[data-label="${label}"] dd`);
}

/** Any deal's latest version, unassigned by the supervisor (AS-2.4), so a test starts from "nobody has it". */
export async function unassigned(loan: string): Promise<Record<string, any>> {
  const overview = await (await api('supervisor-1', `/applications/${loan}`)).json();
  const res = await api('supervisor-1', `/versions/${overview.applicationVersionId}/assignee`, {
    method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({funderId: null}),
  });
  expect(res.ok, `unassign ${loan}: ${res.status}`).toBeTruthy();
  return {...overview, assignedFunder: null};
}

type Overview = Record<string, any>;

/** The deal's overview, read straight from the funder API (as the supervisor, who may read any deal). */
export async function overviewOf(loan: string): Promise<Overview> {
  return (await api('supervisor-1', `/applications/${loan}`)).json();
}

/** Polls the overview until `done` holds and the workflow has stopped acting (AS-2.2's `inProgress`). */
export async function until(loan: string, done: (o: Overview) => boolean, what: string, timeoutMs = 60_000): Promise<Overview> {
  const deadline = Date.now() + timeoutMs;
  let o = await overviewOf(loan);
  while (!(done(o) && !o.inProgress && o.workflowStep === 'WAITING_FOR_FUNDER')) {
    if (Date.now() > deadline) throw new Error(`${loan} never became ${what}: ${o.status}, ${o.workflowStep}`);
    await new Promise((r) => setTimeout(r, 500));
    o = await overviewOf(loan);
  }
  return o;
}

/**
 * T304: puts a seeded deal back as a fresh database has it (the dev reseed, local profile only),
 * submits it, and waits until its run has settled at `status`. For tests that fund or re-stipulate
 * a seeded deal: a FUNDED deal can't be resubmitted (C-24), and AS-10.3 needs it as seeded.
 */
export async function asSeeded(loan: string, status: string): Promise<Overview> {
  const dev = `${FUNDER_API}/dev/applications/${loan}`;
  const reseed = await fetch(`${dev}/reseed`, {method: 'POST'});
  expect(reseed.ok, `reseed ${loan}: ${reseed.status} ${await reseed.clone().text()}`).toBeTruthy();
  const submit = await fetch(`${dev}/submit`, {method: 'POST'});
  expect(submit.ok, `submit ${loan}: ${submit.status}`).toBeTruthy();
  return until(loan, (o) => o.status === status, status);
}

/** LA-1006 as seeded (STIPULATED, its price stip PENDING, nobody on it); reseeded only if it isn't. */
export async function seededLa1006(): Promise<Overview> {
  const o = await overviewOf('LA-1006');
  const stips = o.status === 'STIPULATED' ? await (await api('supervisor-1', `/versions/${o.applicationVersionId}/stipulations`)).json() : [];
  const pristine = stips.length === 1 && stips[0].stipCode === 'STIP_PRICE_MISMATCH' && stips[0].status === 'PENDING';
  return pristine ? unassigned('LA-1006') : asSeeded('LA-1006', 'STIPULATED');
}
