import {expect, test, type Page} from '@playwright/test';

// The walking skeleton through the real stack (spec 002 D1): sign in on the OIDC provider's login
// page (Authorization Code + PKCE), open a deal by loan id, see the overview the agent builds in code
// (A2UI v1.0, no LLM) from the funder API with the user's own token, and Take the deal.
// Needs the funding app (make run, with LA-1006 submitted), the OIDC provider with
// scripts/oidc-config.json (scripts/oidc-env.sh) and the agent (`uv run python main.py` in dashboard/agent).
const ISSUER = process.env.OIDC_ISSUER_URI ?? 'http://127.0.0.1:8090/funding';
const FUNDER_API = process.env.FUNDER_API_URL ?? 'http://localhost:8080/api/v1';

/** A token without a browser, only to set up data straight through the funder API. */
async function tokenFor(user: string): Promise<string> {
  const res = await fetch(`${ISSUER}/token`, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({grant_type: 'client_credentials', client_id: user, client_secret: 'unused', scope: 'funding-api'}),
  });
  expect(res.ok, `token for ${user}`).toBeTruthy();
  return (await res.json()).access_token as string;
}

/**
 * LA-1006's latest version, unassigned: a supervisor's unassign through the funder API (AS-2.4),
 * so every run starts from a deal nobody has taken, without adding versions.
 */
async function unassignedLa1006(): Promise<number> {
  const auth = {Authorization: `Bearer ${await tokenFor('supervisor-1')}`};
  const overview = await (await fetch(`${FUNDER_API}/applications/LA-1006`, {headers: auth})).json();
  const res = await fetch(`${FUNDER_API}/versions/${overview.applicationVersionId}/assignee`, {
    method: 'PUT', headers: {...auth, 'Content-Type': 'application/json'}, body: JSON.stringify({funderId: null}),
  });
  expect(res.ok, `unassign LA-1006: ${res.status} ${await res.clone().text()}`).toBeTruthy();
  return overview.applicationVersionId as number;
}

/** Takes the deal straight through the funder API, as another user would from their own screen. */
async function takeAs(user: string, versionId: number) {
  const res = await fetch(`${FUNDER_API}/versions/${versionId}/take`, {
    method: 'POST', headers: {Authorization: `Bearer ${await tokenFor(user)}`},
  });
  expect(res.ok, `take as ${user}`).toBeTruthy();
}

/** AS-1.1: the real sign-in, on the provider's own login page. */
async function signInAs(page: Page, user: string) {
  await page.goto('/');
  await page.getByRole('button', {name: 'Sign in'}).click();
  await page.locator('input[name="username"]').fill(user);          // mock-oauth2-server's login form
  await page.getByRole('button', {name: 'Sign-in'}).click();
  await expect(page.getByTestId('user')).toHaveText(user);
  expect(new URL(page.url()).pathname).toBe('/');                    // the callback cleaned the URL
}

async function openDeal(page: Page, loanId: string) {
  await page.getByLabel('Loan id').fill(loanId);
  await page.getByRole('button', {name: 'Open'}).click();
}

function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}

test('AS_1_1 sign in, open LA-1006 and see its overview', async ({page}) => {
  const errors = watchErrors(page);
  await unassignedLa1006();
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');

  await expect(page.getByText(/^LA-1006 · v\d+$/)).toBeVisible();          // formatString over the data model
  await expect(page.locator('.status-badge')).toContainText('Status: ');   // StatusBadge, written out (NFR-04)
  await expect(page.getByText('Dealer: Lone Star Motors')).toBeVisible();
  await expect(page.getByText(/^Expiry: /)).toBeVisible();
  await expect(page.getByText(/^Payment: /)).toBeVisible();
  await expect(page.getByRole('columnheader', {name: 'Version'})).toBeVisible();   // AS-2.3, our DataTable
  await expect(page.getByRole('cell', {name: 'latest'})).toBeVisible();
  await expect(page.getByRole('cell', {name: /^superseded by v\d+$/}).first()).toBeVisible();
  await expect(page.getByText('Assigned to: Unassigned')).toBeVisible();
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_6 Take asks for confirmation, then the deal is yours and Take is gone', async ({page}) => {
  const errors = watchErrors(page);
  await unassignedLa1006();
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');

  await page.getByRole('button', {name: 'Take this deal'}).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText(/Take LA-1006 v\d+\? It will be assigned to you\./);
  await expect(page.getByText('Assigned to: Unassigned')).toBeVisible();     // nothing happens before confirming
  await dialog.getByRole('button', {name: 'Yes, take it'}).click();

  await expect(page.getByText('Assigned to: funder-1 (you)')).toBeVisible(); // written as the token's user, then re-read
  await expect(page.getByRole('dialog')).toHaveCount(0);                     // the rebuilt surface closed it
  await expect(page.getByRole('button', {name: 'Take this deal'})).toHaveCount(0);
  await expect(page.getByTestId('events')).toContainText('deal.take');
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_6 if someone else took it first, nothing changes and the overview says who has it', async ({page}) => {
  const versionId = await unassignedLa1006();
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');
  await expect(page.getByRole('button', {name: 'Take this deal'})).toBeVisible();
  await takeAs('funder-2', versionId);                                       // meanwhile, on another screen

  await page.getByRole('button', {name: 'Take this deal'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Yes, take it'}).click();
  await expect(page.getByText('Someone else took this deal first. The overview now shows who has it.')).toBeVisible();
  await expect(page.getByText('Assigned to: funder-2')).toBeVisible();
  await expect(page.getByRole('button', {name: 'Take this deal'})).toHaveCount(0);
});

test('AS_1_5 an unknown loan id shows "not found", not an error page', async ({page}) => {
  const errors = watchErrors(page);
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-404');
  await expect(page.getByText("That deal doesn't exist. Check the loan id.")).toBeVisible();
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_1_1 a user who is not in the directory is told so', async ({page}) => {
  await signInAs(page, 'stranger');
  await openDeal(page, 'LA-1006');
  await expect(page.getByText(/isn't set up for the dashboard/)).toBeVisible();   // 403 UNKNOWN_USER
  await expect(page.getByText(/^LA-1006 · v/)).toHaveCount(0);
});

test('FR_11 signed out there is only the sign-in screen, and signing out returns to it', async ({page}) => {
  await page.goto('/');
  await expect(page.getByRole('button', {name: 'Sign in'})).toBeVisible();
  await expect(page.getByLabel('Loan id')).toHaveCount(0);
  await signInAs(page, 'supervisor-1');
  await page.getByRole('button', {name: 'Sign out'}).click();
  await expect(page.getByRole('button', {name: 'Sign in'})).toBeVisible();
});
