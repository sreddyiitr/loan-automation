import {expect, test, type Page} from '@playwright/test';

// The A2UI round trip through the real stack (spec 002 D0, then T104): the browser asks the agent
// over A2A with the user's bearer token; the agent relays it to the funder API and answers with an
// A2UI v1.0 surface built by code (no LLM); a click goes back as an action and is written through
// the funder API. Needs the funding app (make run, with LA-1006 submitted), the OIDC provider
// (scripts/oidc-env.sh) and the agent (`uv run python main.py` in dashboard/agent).
const OIDC_TOKEN_URL = process.env.OIDC_ISSUER_URI
  ? `${process.env.OIDC_ISSUER_URI}/token` : 'http://127.0.0.1:8090/funding/token';

const FUNDER_API = process.env.FUNDER_API_URL ?? 'http://localhost:8080/api/v1';

/** A real access token from the mock provider. */
async function tokenFor(user: string): Promise<string> {
  const res = await fetch(OIDC_TOKEN_URL, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({grant_type: 'client_credentials', client_id: user, client_secret: 'unused', scope: 'funding-api'}),
  });
  expect(res.ok, `token for ${user}`).toBeTruthy();
  return (await res.json()).access_token as string;
}

/** Puts the user's token in the page's memory (T105 replaces this with the browser sign-in). */
async function signInAs(page: Page, user: string) {
  const token = await tokenFor(user);
  await page.addInitScript((t) => { window.__FUNDER_TOKEN__ = t; }, token);
}

/** Straight through the funder API, so the test starts from a known assignee. */
async function assignLa1006To(user: string) {
  const auth = {Authorization: `Bearer ${await tokenFor(user)}`};
  const overview = await (await fetch(`${FUNDER_API}/applications/LA-1006`, {headers: auth})).json();
  const res = await fetch(`${FUNDER_API}/versions/${overview.applicationVersionId}/assignee`, {
    method: 'PUT', headers: {...auth, 'Content-Type': 'application/json'}, body: JSON.stringify({funderId: user}),
  });
  expect(res.ok, `assign LA-1006 to ${user}`).toBeTruthy();
}

function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}

test('AS_2_1 the overview renders from the funder API, and Take writes through it', async ({page}) => {
  const errors = watchErrors(page);
  await assignLa1006To('funder-2');                                       // so Take visibly changes it
  await signInAs(page, 'funder-1');
  await page.goto('/');
  await expect(page.getByText('Assigned to: funder-2')).toBeVisible();
  await expect(page.getByText(/^LA-1006 · v\d+$/)).toBeVisible();          // formatString over the data model
  await expect(page.getByText(/^Status: /)).toBeVisible();
  await expect(page.getByText('Dealer: Lone Star Motors')).toBeVisible();
  await expect(page.getByText(/^Expiry: /)).toBeVisible();
  await expect(page.getByText(/^Payment: /)).toBeVisible();
  // AS-2.3: version history in our custom DataTable
  await expect(page.getByRole('columnheader', {name: 'Version'})).toBeVisible();
  await expect(page.getByRole('cell', {name: 'latest'})).toBeVisible();

  await page.getByRole('button', {name: 'Take this deal'}).click();
  const events = page.getByTestId('events');
  await expect(events).toContainText('deal.take');
  await expect(events).toContainText('LA-1006');                            // context bound with @path, resolved by the renderer
  await expect(page.getByText('Assigned to: funder-1')).toBeVisible();     // written through the funder API as the token's user, then re-read
  expect(errors, errors.join('\n')).toEqual([]);
});

test('FR_11 without a token the agent asks the user to sign in', async ({page}) => {
  const errors = watchErrors(page);
  await page.goto('/');
  await expect(page.getByText('Sign in to use the funder dashboard.')).toBeVisible();
  await expect(page.getByText(/^LA-1006/)).toHaveCount(0);
  expect(errors, errors.join('\n')).toEqual([]);
});
