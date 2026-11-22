import {readdirSync, readFileSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {expect, test} from '@playwright/test';
import {api, fact, openDeal, signInAs, takeAs, unassignedLa1006, watchErrors} from './support';

// Spec 002 D1, the walking skeleton through the real stack: sign in on the OIDC provider's login
// page (Authorization Code + PKCE), open a deal by loan id, see the overview the agent builds in code
// (A2UI v1.0, no LLM) from the funder API with the user's own token, and Take the deal.
// Test names trace to the acceptance scenarios (constitution P-1): AS_x_y_… is AS-x.y.
// Needs the funding API (seeds submitted) and the OIDC provider with scripts/oidc-config.json:
// `make dashboard`. Playwright starts its own agent and web app (playwright.config.ts).
const SCENARIOS = fileURLToPath(new URL('../../../test/data/scenarios', import.meta.url));

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
  await signInAs(page, 'docs-1');                                    // no identity typed anywhere else
  await openDeal(page, 'LA-1006');
  await page.getByRole('button', {name: 'Take this deal'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Yes, take it'}).click();
  await expect(fact(page, 'Assigned to')).toHaveText('docs-1 (you)');

  const audit = await (await api('supervisor-1', `/versions/${deal.applicationVersionId}/audit`)).json();
  const last = audit.at(-1);
  expect(last.action).toBe('ASSIGNEE_CHANGED');
  expect(last.actor).toBe('docs-1');                                 // the token's sub, through the agent
  expect(last.afterState.assignedFunder).toBe('docs-1');
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
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-404');
  await expect(page.getByText("That deal doesn't exist. Check the loan id.")).toBeVisible();
  await expect(page.locator('.status-badge')).toHaveCount(0);
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_1_theOverviewShowsTheDealFactsExpiryAndPayment', async ({page}) => {
  const errors = watchErrors(page);
  const deal = await unassignedLa1006();                               // the API is the source of each value
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');

  await expect(page.getByText(`LA-1006 · v${deal.latestVersionNumber}`, {exact: true})).toBeVisible();
  await expect(page.locator('.status-badge')).toHaveAttribute('data-status', deal.status);
  await expect(page.locator('.status-badge')).toHaveText(`Status: ${deal.status.replaceAll('_', ' ').toLowerCase()}`);
  for (const [label, value] of [
    ['Dealer', deal.dealershipName],
    ['Borrower', deal.borrowerName],
    ['Co-borrower', deal.coBorrowerName ?? 'None'],
    ['Stated income', money(deal.statedIncomeAnnual)],
    ['VIN', deal.vehicleVin],
    ['Assigned to', 'Unassigned'],
    ['Submitted', deal.submittedAt.slice(0, 10)],
  ]) await expect(fact(page, label), label).toHaveText(value);
  await expect(fact(page, 'Expiry')).toHaveText(new RegExp(`^(\\d+ days? left|Expires today) \\(${deal.expiresAt.slice(0, 10)}\\)$`));
  expect(deal.payment.state).toBe('NOT_STARTED');                      // LA-1006 is STIPULATED: nothing paid yet
  await expect(fact(page, 'Payment')).toHaveText('Not started');
  await expect(page.getByText(`${deal.dealershipName} · submitted ${deal.submittedAt.slice(0, 10)}`)).toBeVisible();   // the header
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_3_versionHistoryListsEveryVersionNewestFirst', async ({page}) => {
  const deal = await unassignedLa1006();
  const versions = await (await api('docs-1', '/applications/LA-1006/versions')).json();
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');

  await expect(page.getByRole('columnheader', {name: 'Version'})).toBeVisible();     // our DataTable
  const rows = page.locator('.data-table table tbody tr');
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
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');
  const popup = page.waitForEvent('popup');
  await page.getByRole('button', {name: 'Open the workflow in Temporal'}).click();
  expect((await popup).url()).toContain(`/workflows/${deal.workflowId}`);
});

test("AS_2_7_whatsBeenDoneShowsEachCheckAndTheMilestonesOfAFundedDeal", async ({page}) => {
  const errors = watchErrors(page);
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1001');                                       // seeded: every check passes, then it's paid
  await expect(page.getByText("What's been done")).toBeVisible();
  const checks = page.locator('.check');
  await expect(checks).toHaveCount(5);
  await expect(checks.locator('.check-label')).toHaveText(['Passed', 'Passed', 'Passed', 'Passed', 'Passed']);
  await page.locator('.check[data-check="TILA math"] .check-head').click();   // the attempt and time are inside
  await expect(page.locator('.check[data-check="TILA math"] .check-detail')).toHaveText(/^attempt 1 · \d{4}-\d{2}-\d{2} \d{2}:\d{2}$/);
  const events = page.locator('.event-title');
  await expect(events.first()).toHaveText('Funded');                     // newest first
  await expect(events).toContainText([/^Paid: DSB-\w+, \$[\d,]+\.\d{2}$/, 'Checks finished: approved', 'Submitted for checks']);
  await expect(page.locator('.event', {hasText: 'Submitted for checks'}).locator('.event-meta')).toContainText('Dealer submission');
  expect(errors, errors.join('\n')).toEqual([]);
});

test("AS_2_7_aStipulatedDealShowsTheFailedCheckAndTheStipTheSystemRaised", async ({page}) => {
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');                                       // seeded: price 1¢ off
  await expect(page.locator('.check[data-check="Price"] .check-label')).toHaveText('Failed');
  const raised = page.locator('.event', {hasText: 'Stip raised: price mismatch'});
  await expect(raised).toHaveCount(1);
  await expect(raised.locator('.event-meta')).toContainText('System');
  await expect(page.locator('.event-title', {hasText: 'Checks finished: stipulated'})).toHaveCount(1);
});

test("AS_2_7_aPassedCheckOpensToShowWhyItPassed", async ({page}) => {
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1001');
  const tila = page.locator('.check[data-check="TILA math"]');
  await expect(tila.locator('.check-reason')).toHaveText('All 3 comparisons match');
  await expect(tila.locator('.comparisons')).toHaveCount(0);            // passed: starts closed
  await tila.locator('.check-head').click();
  const row = tila.locator('.comparisons tbody tr', {hasText: 'amount financed + finance charge = total of payments'});
  await expect(row.locator('td')).toHaveText(['✓matches', 'amount financed + finance charge = total of payments',
                                              /^\d+\.\d{2}$/, /^\d+\.\d{2}$/]);
  const [, , expected, found] = await row.locator('td').allTextContents();
  expect(found).toBe(expected);                                         // why it passed: the sides agree
  await tila.locator('.check-head').click();
  await expect(tila.locator('.comparisons')).toHaveCount(0);            // and closes again
});

test("AS_2_7_checksThatDidntPassStartOpenAndSayWhy", async ({page}) => {
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1002');                                       // seeded: TILA total off by $10
  const tila = page.locator('.check[data-check="TILA math"]');
  await expect(tila.locator('.check-reason')).toHaveText("2 of 3 comparisons don't match");
  await expect(tila.locator('.comparisons tr.bad')).toHaveCount(2);     // open without a click
  await openDeal(page, 'LA-1023');                                       // seeded: low-confidence cash price
  await expect(page.locator('.check[data-check="Price"] .check-reason')).toHaveText('Low-confidence field: RISC cash price');
  await openDeal(page, 'LA-1016');                                       // seeded: income API down
  await expect(page.locator('.check[data-check="Income"] .check-reason')).toHaveText("Couldn't finish: income API: server error (503)");
});

test('AS_2_6_takeAsksForConfirmationThenTheDealIsYoursAndTakeIsGone', async ({page}) => {
  const errors = watchErrors(page);
  await unassignedLa1006();
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');

  await page.getByRole('button', {name: 'Take this deal'}).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText(/Take LA-1006 v\d+\? It will be assigned to you\./);
  await expect(fact(page, 'Assigned to')).toHaveText('Unassigned');     // nothing happens before confirming
  await dialog.getByRole('button', {name: 'Yes, take it'}).click();

  await expect(fact(page, 'Assigned to')).toHaveText('docs-1 (you)'); // written as the token's user, then re-read
  await expect(page.getByRole('dialog')).toHaveCount(0);                     // the rebuilt surface closed it
  await expect(page.getByRole('button', {name: 'Take this deal'})).toHaveCount(0);
  await expect(page.getByTestId('events')).toContainText('deal.take');
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_2_6_ifSomeoneElseTookItFirstNothingChangesAndTheOverviewSaysWhoHasIt', async ({page}) => {
  const deal = await unassignedLa1006();
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');
  await expect(page.getByRole('button', {name: 'Take this deal'})).toBeVisible();
  await takeAs('supervisor-1', deal.applicationVersionId);                       // meanwhile, on another screen

  await page.getByRole('button', {name: 'Take this deal'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Yes, take it'}).click();
  await expect(page.getByText('Someone else took this deal first. The overview now shows who has it.')).toBeVisible();
  await expect(fact(page, 'Assigned to')).toHaveText('supervisor-1');
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
  await signInAs(page, 'docs-1');
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
