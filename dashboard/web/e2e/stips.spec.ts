import {expect, test, type Page} from '@playwright/test';
import {api, asSeeded, openDeal, overviewOf, seededLa1006, signInAs, takeAs, unassigned, until, watchErrors} from './support';

// Spec 002 D3 (T304): the stip list on the deal page. Satisfy and Waive need a reason (AS-3.2), Waive
// isn't offered for a RISC stip (AS-3.3), a stale screen that loses the race says who won (AS-3.4), and
// Add stip picks from the API's code list (AS-3.5).
// These tests fund LA-1006 and re-stipulate LA-1030; afterAll puts both back as seeded (the dev
// reseed), so the next run and AS-10.3 find them as a fresh database has them. Files run one at a time
// (playwright.config.ts), so no other test sees them changed.

const stip = (page: Page, code: string) => page.locator(`.stip-list .suggestion[data-stip="${code}"]`);
const badge = (page: Page) => page.locator('.status-badge');
const DATE = String.raw`\d{4}-\d{2}-\d{2}`;

/** Marks the page, so a test can prove it was never reloaded (AS-3.2: "without a manual reload"). */
async function mark(page: Page) {
  await page.evaluate(() => { (window as unknown as {t304: boolean}).t304 = true; });
}
async function stillMarked(page: Page) {
  expect(await page.evaluate(() => (window as unknown as {t304?: boolean}).t304), 'the page was reloaded').toBe(true);
}

/** Opens the reason box for a stip's Satisfy or Waive, checks the reason is required, then confirms. */
async function resolve(page: Page, code: string, verb: 'Satisfy' | 'Waive', reason: string) {
  await stip(page, code).getByRole('button', {name: `${verb} ${code}`}).click();
  const box = page.getByRole('group', {name: `${verb} ${code}`});
  await expect(box.getByRole('button', {name: 'Confirm'})).toBeDisabled();       // no reason, no write
  await box.getByLabel('Reason').fill('   ');
  await expect(box.getByRole('button', {name: 'Confirm'})).toBeDisabled();       // blank isn't a reason
  await box.getByLabel('Reason').fill(reason);
  await box.getByRole('button', {name: 'Confirm'}).click();
}

test.afterAll(async () => {
  await seededLa1006();                                    // funded (AS-3.2), or its price stip satisfied (AS-3.4)
  if ((await overviewOf('LA-1030')).status !== 'APPROVED') await asSeeded('LA-1030', 'APPROVED');
  else await unassigned('LA-1030');
});

test('AS_3_2_waivingLa1006sPriceStipWithAReasonFundsItAndTheOverviewShowsFundedWithoutAReload', async ({page}) => {
  const errors = watchErrors(page);
  const deal = await seededLa1006();
  await takeAs('docs-1', deal.applicationVersionId);
  await signInAs(page, 'docs-1');
  await openDeal(page, 'LA-1006');
  await expect(badge(page)).toHaveAttribute('data-status', 'STIPULATED');
  const price = stip(page, 'STIP_PRICE_MISMATCH');
  await expect(price).toHaveAttribute('data-status', 'PENDING');
  await expect(price).toContainText("Price differs between the RISC and the Buyer's Order");
  await expect(price).toContainText('Price check · BUYERS ORDER');                // AS-3.1: what raised it
  await mark(page);

  await resolve(page, 'STIP_PRICE_MISMATCH', 'Waive', 'Dealer letter: the 1¢ is rounding');
  await expect(price).toHaveAttribute('data-status', 'WAIVED');
  await expect(price).toContainText(new RegExp(`Waived by docs-1 · ${DATE} · “Dealer letter: the 1¢ is rounding”`));
  await expect(page.getByText('Stip waived.')).toBeVisible();
  await expect(badge(page)).toHaveAttribute('data-status', 'FUNDED', {timeout: 20_000});   // the agent follows the workflow (FR-10)
  await stillMarked(page);

  const after = await overviewOf('LA-1006');                                       // the API agrees
  expect(after.status).toBe('FUNDED');
  expect(after.payment.state).toBe('PAID');
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_3_3_aRiscStipOffersNoWaiveAndSaysTheDealerMustSendACorrectedRisc', async ({page}) => {
  const errors = watchErrors(page);
  const deal = await unassigned('LA-1002');                                        // TILA math: RISC work
  await takeAs('risc-1', deal.applicationVersionId);                               // its specialist, so Waive is hidden for the stip, not the user
  try {
    await signInAs(page, 'risc-1');
    await openDeal(page, 'LA-1002');
    const tila = stip(page, 'STIP_TILA_MATH_DISCREPANCY');
    await expect(tila).toHaveAttribute('data-status', 'PENDING');
    await expect(tila).toContainText("Can't be waived: the dealer has to send a corrected RISC.");
    await expect(tila.getByRole('button', {name: /^Waive /})).toHaveCount(0);
    await expect(tila.getByRole('button', {name: /^Satisfy /})).toHaveCount(0);   // the API refuses that too (spec 001 AS-8.10)
    await expect(page.getByRole('button', {name: 'Add stip'})).toBeEnabled();      // the same user may write to this deal

    const [s] = await (await api('risc-1', `/versions/${deal.applicationVersionId}/stipulations`)).json();
    const res = await api('risc-1', `/versions/${deal.applicationVersionId}/stipulations/${s.stipId}`, {
      method: 'PATCH', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({status: 'WAIVED', reason: 'try'}),
    });
    expect(res.status).toBe(409);                                                  // hidden because the API would refuse it
    expect((await res.json()).reason).toBe('RISC_STIP_NOT_WAIVABLE');
    expect(errors, errors.join('\n')).toEqual([]);
  } finally {
    await unassigned('LA-1002');                                                   // as seeded: My work and the assistant expect it unassigned
  }
});

test('AS_3_4_whenTwoFundersResolveTheSameStipTheSecondIsToldWhoWonAndWhenWithNoRawError', async ({browser}) => {
  const deal = await seededLa1006();
  await takeAs('docs-1', deal.applicationVersionId);
  // A second pending stip keeps the deal open after the first resolution, so the loser's refusal is
  // ALREADY_RESOLVED (the stip), not VERSION_TERMINAL (a funded deal). Documents work, so it stays docs-1's.
  const added = await api('supervisor-1', `/versions/${deal.applicationVersionId}/stipulations`, {
    method: 'POST', headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({code: 'STIP_VIN_MISMATCH', description: 'Check the VIN plate photo'}),
  });
  expect(added.ok, `add stip: ${added.status}`).toBeTruthy();

  const winner = await (await browser.newContext()).newPage();
  const loser = await (await browser.newContext()).newPage();                     // another browser: its own sign-in
  const winnerErrors = watchErrors(winner);
  const loserErrors = watchErrors(loser);
  await signInAs(winner, 'docs-1');
  await signInAs(loser, 'supervisor-1');
  for (const p of [winner, loser]) {
    await openDeal(p, 'LA-1006');
    await expect(stip(p, 'STIP_PRICE_MISMATCH')).toHaveAttribute('data-status', 'PENDING');
  }

  await resolve(winner, 'STIP_PRICE_MISMATCH', 'Satisfy', 'Corrected Buyer\'s Order received');
  await expect(stip(winner, 'STIP_PRICE_MISMATCH')).toHaveAttribute('data-status', 'SATISFIED');

  // the loser's screen is stale: it still offers Satisfy
  await expect(stip(loser, 'STIP_PRICE_MISMATCH')).toHaveAttribute('data-status', 'PENDING');
  await resolve(loser, 'STIP_PRICE_MISMATCH', 'Satisfy', 'Looks fine to me');
  await expect(loser.getByText('Someone else resolved this stip first. The list now shows who, when and why.')).toBeVisible();
  const row = stip(loser, 'STIP_PRICE_MISMATCH');
  await expect(row).toHaveAttribute('data-status', 'SATISFIED');                  // refreshed
  await expect(row).toContainText(new RegExp(`Satisfied by docs-1 · ${DATE} · “Corrected Buyer's Order received”`));
  await expect(loser.locator('body')).not.toContainText(/ALREADY_RESOLVED|409|problem\+json|Error:/);
  await expect(badge(loser)).toHaveAttribute('data-status', 'STIPULATED');        // the VIN stip still holds it

  const [s] = (await (await api('supervisor-1', `/versions/${deal.applicationVersionId}/stipulations`)).json())
    .filter((x: {stipCode: string}) => x.stipCode === 'STIP_PRICE_MISMATCH');
  expect([s.status, s.resolvedBy, s.resolutionNote]).toEqual(['SATISFIED', 'docs-1', "Corrected Buyer's Order received"]);
  expect([...winnerErrors, ...loserErrors], [...winnerErrors, ...loserErrors].join('\n')).toEqual([]);
});

test('AS_3_5_addingAStipFromTheCodeListMovesAnApprovedDealToStipulated', async ({page}) => {
  const errors = watchErrors(page);
  const now = await overviewOf('LA-1030');                                         // payment failed: held APPROVED (C-8)
  const deal = now.status === 'APPROVED' ? await until('LA-1030', (o) => o.status === 'APPROVED', 'APPROVED')
    : await asSeeded('LA-1030', 'APPROVED');
  await unassigned('LA-1030');
  await takeAs('payment-1', deal.applicationVersionId);
  await signInAs(page, 'payment-1');
  await openDeal(page, 'LA-1030');
  await expect(badge(page)).toHaveAttribute('data-status', 'APPROVED');
  await expect(page.getByText('No stips on this version.')).toBeVisible();
  await mark(page);

  await page.getByRole('button', {name: 'Add stip'}).click();
  const form = page.getByRole('group', {name: 'Add stip'});
  const codes = (await (await api('payment-1', '/stip-codes')).json()).map((c: {code: string}) => c.code);
  const offered = await form.getByLabel('Stip code').locator('option').evaluateAll(
    (os) => os.map((o) => (o as HTMLOptionElement).value).filter(Boolean));
  expect(offered).toEqual(codes);                                                  // exactly the API's list (D-19)
  expect(offered).toEqual(expect.arrayContaining(['STIP_DEALER_LETTER', 'STIP_OTHER']));
  await expect(form.getByRole('button', {name: 'Add'})).toBeDisabled();            // a code and a description first
  await form.getByLabel('Stip code').selectOption('STIP_DEALER_LETTER');
  await expect(form.getByRole('button', {name: 'Add'})).toBeDisabled();
  await form.getByLabel('Description').fill('Letter confirming the payee account');
  await form.getByRole('button', {name: 'Add'}).click();

  const added = stip(page, 'STIP_DEALER_LETTER');
  await expect(added).toHaveAttribute('data-status', 'PENDING');
  await expect(added).toContainText('Letter confirming the payee account');
  await expect(added).toContainText('Added by a funder');
  await expect(page.getByText('Stip added.')).toBeVisible();
  await expect(badge(page)).toHaveAttribute('data-status', 'STIPULATED');
  await stillMarked(page);
  expect((await overviewOf('LA-1030')).status).toBe('STIPULATED');
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_3_5_whenAddingAStipIsntAllowedTheButtonIsDisabledWithTheReason', async ({page}) => {
  const errors = watchErrors(page);
  const deal = await unassigned('LA-1007');                                        // documents work nobody has
  await takeAs('docs-1', deal.applicationVersionId);
  try {
    await signInAs(page, 'gap-1');                                                 // not docs-1's deal, nor gap-1's kind of work
    await openDeal(page, 'LA-1007');
    await expect(page.getByRole('button', {name: 'Add stip'})).toBeDisabled();
    const reason = await page.getByRole('button', {name: 'Add stip'}).getAttribute('title');
    expect(reason).toBeTruthy();
    await expect(page.locator('.stip-list').getByText(reason!, {exact: true})).toBeVisible();   // said, not only on hover
    await expect(page.locator('.stip-list').getByRole('button', {name: /^(Satisfy|Waive) /})).toHaveCount(0);
    expect(errors, errors.join('\n')).toEqual([]);
  } finally {
    await unassigned('LA-1007');
  }
});
