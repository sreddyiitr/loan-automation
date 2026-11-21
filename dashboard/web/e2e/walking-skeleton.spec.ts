import {readdirSync, readFileSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {expect, test, type Page} from '@playwright/test';

// Spec 002 D1, the walking skeleton through the real stack: sign in on the OIDC provider's login
// page (Authorization Code + PKCE), open a deal by loan id, see the overview the agent builds in code
// (A2UI v1.0, no LLM) from the funder API with the user's own token, and Take the deal.
// Test names trace to the acceptance scenarios (constitution P-1): AS_x_y_… is AS-x.y.
// Needs `make dashboard` running (or make run with the seeds submitted, the agent, and the OIDC
// provider with scripts/oidc-config.json). Playwright reuses its web app, or starts its own.
const ISSUER = process.env.OIDC_ISSUER_URI ?? 'http://127.0.0.1:8090/funding';
const FUNDER_API = process.env.FUNDER_API_URL ?? 'http://localhost:8080/api/v1';
const SCENARIOS = fileURLToPath(new URL('../../../test/data/scenarios', import.meta.url));

/** A token without a browser, only to set up or read data straight through the funder API. */
async function tokenFor(user: string): Promise<string> {
  const res = await fetch(`${ISSUER}/token`, {
    method: 'POST',
    headers: {'Content-Type': 'application/x-www-form-urlencoded'},
    body: new URLSearchParams({grant_type: 'client_credentials', client_id: user, client_secret: 'unused', scope: 'funding-api'}),
  });
  expect(res.ok, `token for ${user}`).toBeTruthy();
  return (await res.json()).access_token as string;
}

async function api(user: string, path: string, init: RequestInit = {}): Promise<Response> {
  return fetch(`${FUNDER_API}${path}`, {...init, headers: {...init.headers, Authorization: `Bearer ${await tokenFor(user)}`}});
}

/**
 * LA-1006's latest version, unassigned: a supervisor's unassign through the funder API (AS-2.4),
 * so every run starts from a deal nobody has taken, without adding versions. Returns the overview.
 */
async function unassignedLa1006(): Promise<Record<string, any>> {
  const overview = await (await api('supervisor-1', '/applications/LA-1006')).json();
  const res = await api('supervisor-1', `/versions/${overview.applicationVersionId}/assignee`, {
    method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({funderId: null}),
  });
  expect(res.ok, `unassign LA-1006: ${res.status} ${await res.clone().text()}`).toBeTruthy();
  return {...overview, assignedFunder: null};
}

/** Takes the deal straight through the funder API, as another user would from their own screen. */
async function takeAs(user: string, versionId: number) {
  const res = await api(user, `/versions/${versionId}/take`, {method: 'POST'});
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
  await page.getByRole('button', {name: 'Open', exact: true}).click();
}

function watchErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));
  return errors;
}

/** Each seeded scenario's id and expected status, read from its intent (P-2), never copied here. */
function seededScenarios(): {id: string; status: string}[] {
  return readdirSync(SCENARIOS).filter((f) => /^LA-\d+.*\.yaml$/.test(f)).map((f) => {
    const text = readFileSync(join(SCENARIOS, f), 'utf8');
    const id = /^id:\s*(\S+)/m.exec(text)?.[1];
    const status = /^expect:\n(?:[ \t]+.*\n)*?[ \t]+status:\s*(\S+)/m.exec(text)?.[1];
    if (!id || !status) throw new Error(`${f}: no id or expect.status`);
    return {id, status};
  }).sort((a, b) => a.id.localeCompare(b.id));
}

const money = (n: number) => n.toLocaleString('en-US', {style: 'currency', currency: 'USD'});

test('AS_1_1_signsInOnTheProviderPageAndEveryWriteIsMadeAsThatUser', async ({page}) => {
  const errors = watchErrors(page);
  const deal = await unassignedLa1006();
  await signInAs(page, 'funder-1');                                    // no identity typed anywhere else
  await openDeal(page, 'LA-1006');
  await page.getByRole('button', {name: 'Take this deal'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Yes, take it'}).click();
  await expect(page.getByText('Assigned to: funder-1 (you)')).toBeVisible();

  const audit = await (await api('supervisor-1', `/versions/${deal.applicationVersionId}/audit`)).json();
  const last = audit.at(-1);
  expect(last.action).toBe('ASSIGNEE_CHANGED');
  expect(last.actor).toBe('funder-1');                                 // the token's sub, through the agent
  expect(last.afterState.assignedFunder).toBe('funder-1');
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_1_1_aUserWhoIsNotInTheDirectoryIsRefused', async ({page}) => {
  await signInAs(page, 'stranger');
  await openDeal(page, 'LA-1006');
  await expect(page.getByText(/isn't set up for the dashboard/)).toBeVisible();   // 403 UNKNOWN_USER
  await expect(page.getByText(/^LA-1006 · v/)).toHaveCount(0);
});

test('AS_1_5_anUnknownLoanIdShowsNotFoundNotAnErrorPage', async ({page}) => {
  const errors = watchErrors(page);
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-404');
  await expect(page.getByText("That deal doesn't exist. Check the loan id.")).toBeVisible();
  await expect(page.locator('.status-badge')).toHaveCount(0);
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_1_theOverviewShowsTheDealFactsExpiryAndPayment', async ({page}) => {
  const errors = watchErrors(page);
  const deal = await unassignedLa1006();                               // the API is the source of each value
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');

  await expect(page.getByText(`LA-1006 · v${deal.latestVersionNumber}`, {exact: true})).toBeVisible();
  await expect(page.locator('.status-badge')).toHaveAttribute('data-status', deal.status);
  await expect(page.locator('.status-badge')).toHaveText(`Status: ${deal.status.replaceAll('_', ' ').toLowerCase()}`);
  for (const fact of [
    `Dealer: ${deal.dealershipName}`,
    `Borrower: ${deal.borrowerName}`,
    `Co-borrower: ${deal.coBorrowerName ?? 'None'}`,
    `Stated income: ${money(deal.statedIncomeAnnual)}`,
    `VIN: ${deal.vehicleVin}`,
    'Assigned to: Unassigned',
    `Submitted: ${deal.submittedAt.slice(0, 10)}`,
  ]) await expect(page.getByText(fact, {exact: true})).toBeVisible();
  await expect(page.getByText(new RegExp(`^Expiry: (\\d+ days? left|Expires today) \\(${deal.expiresAt.slice(0, 10)}\\)$`))).toBeVisible();
  expect(deal.payment.state).toBe('NOT_STARTED');                      // LA-1006 is STIPULATED: nothing paid yet
  await expect(page.getByText('Payment: Not started', {exact: true})).toBeVisible();
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_3_versionHistoryListsEveryVersionNewestFirst', async ({page}) => {
  const deal = await unassignedLa1006();
  const versions = await (await api('funder-1', '/applications/LA-1006/versions')).json();
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');

  await expect(page.getByRole('columnheader', {name: 'Version'})).toBeVisible();     // our DataTable
  const rows = page.locator('table tbody tr');
  await expect(rows).toHaveCount(versions.length);
  const latest = deal.latestVersionNumber;
  await expect(rows.first()).toContainText(`v${latest}`);
  await expect(rows.first()).toContainText('latest');
  if (latest > 1) {
    await expect(rows.nth(1)).toContainText(`v${latest - 1}`);
    await expect(rows.nth(1)).toContainText(`superseded by v${latest}`);
  }
});

test('AS_2_5_theOverviewLinksToItsWorkflowInTheTemporalUi', async ({page}) => {
  const deal = await unassignedLa1006();
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');
  const popup = page.waitForEvent('popup');
  await page.getByRole('button', {name: 'Open the workflow in Temporal'}).click();
  expect((await popup).url()).toContain(`/workflows/${deal.workflowId}`);
});

test('AS_2_6_takeAsksForConfirmationThenTheDealIsYoursAndTakeIsGone', async ({page}) => {
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

test('AS_2_6_ifSomeoneElseTookItFirstNothingChangesAndTheOverviewSaysWhoHasIt', async ({page}) => {
  const deal = await unassignedLa1006();
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');
  await expect(page.getByRole('button', {name: 'Take this deal'})).toBeVisible();
  await takeAs('funder-2', deal.applicationVersionId);                       // meanwhile, on another screen

  await page.getByRole('button', {name: 'Take this deal'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Yes, take it'}).click();
  await expect(page.getByText('Someone else took this deal first. The overview now shows who has it.')).toBeVisible();
  await expect(page.getByText('Assigned to: funder-2')).toBeVisible();
  await expect(page.getByRole('button', {name: 'Take this deal'})).toHaveCount(0);
});

// AS-10.3, the part D1 can show: every seeded scenario is in the dashboard with the status its
// intent expects, with no setup beyond `make dashboard`. Waiving LA-1006's price stip is the other
// half; it needs the stips tab, so it's T304's AS_3_2 (D3).
// The expected statuses hold on a fresh database. If local data has moved on (say, LA-1006 waived
// by hand), this fails and names the deal.
test('AS_10_3_onFirstLaunchEverySeededScenarioOpensWithItsExpectedStatus', async ({page}) => {
  test.setTimeout(120_000);
  const errors = watchErrors(page);
  const scenarios = seededScenarios();
  expect(scenarios.length).toBeGreaterThan(30);
  await signInAs(page, 'funder-1');
  for (const {id, status} of scenarios) {
    await openDeal(page, id);
    await expect(page.getByText(new RegExp(`^${id} · v\\d+$`)), `${id} opens`).toBeVisible();
    await expect(page.locator('.status-badge'), `${id} is ${status}, as its scenario expects`)
      .toHaveAttribute('data-status', status);
  }
  expect(errors, errors.join('\n')).toEqual([]);
});

test('FR_11_signedOutThereIsOnlyTheSignInScreenAndSigningOutReturnsToIt', async ({page}) => {
  await page.goto('/');
  await expect(page.getByRole('button', {name: 'Sign in'})).toBeVisible();
  await expect(page.getByLabel('Loan id')).toHaveCount(0);
  await signInAs(page, 'supervisor-1');
  await page.getByRole('button', {name: 'Sign out'}).click();
  await expect(page.getByRole('button', {name: 'Sign in'})).toBeVisible();
});
