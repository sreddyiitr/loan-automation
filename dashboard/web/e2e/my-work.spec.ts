import {expect, test, type Page} from '@playwright/test';
import {fact, signInAs, unassigned, watchErrors} from './support';

// Spec 002 D6a (AS-1.8): signing in lands on "My work": what's assigned to you and what's ready for you
// to take, each deal with what's missing, opened with a click (AS-1.5).

const assigned = (page: Page) => page.locator('.work-list').filter({hasText: 'Assigned to you'});
const ready = (page: Page) => page.locator('.work-list').filter({hasText: 'Ready for you to take'});
const card = (list: ReturnType<typeof ready>, loan: string) => list.locator(`.work-card[data-loan="${loan}"]`);

test('AS_1_8_aSpecialistLandsOnTheirWorkOpensADealWithAClickAndFindsItAssignedAfterTakingIt', async ({page}) => {
  const errors = watchErrors(page);
  // LA-1032: a price mismatch (documents work) that no other test touches, so files can run in parallel
  await unassigned('LA-1032');
  await signInAs(page, 'docs-1');

  // no typing: the lists are there after sign-in
  await expect(ready(page)).toBeVisible();
  const la1032 = card(ready(page), 'LA-1032');
  await expect(la1032).toBeVisible();
  await expect(la1032.locator('.work-chip')).toHaveText(["Price differs between the RISC and the Buyer's Order"]);
  await expect(la1032.locator('.work-status')).toHaveAttribute('data-status', 'STIPULATED');
  await expect(la1032).toContainText(/Lone Star Motors · \d+ days left/);
  await expect(card(ready(page), 'LA-1018')).toHaveCount(0);           // GAP work isn't docs-1's

  // a click opens the deal (AS-1.5), and the lists make way for it
  await la1032.click();
  await expect(page.getByText(/^LA-1032 · v\d+$/)).toBeVisible();
  await expect(ready(page)).toHaveCount(0);
  await page.getByRole('button', {name: 'Take this deal'}).click();
  await page.getByRole('dialog').getByRole('button', {name: 'Yes, take it'}).click();
  await expect(fact(page, 'Assigned to')).toHaveText('docs-1 (you)');

  // back to My work: refreshed, and the deal is now assigned
  await page.getByRole('button', {name: 'My work'}).click();
  await expect(card(assigned(page), 'LA-1032')).toBeVisible();
  await expect(card(ready(page), 'LA-1032')).toHaveCount(0);
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_1_8_theSupervisorSeesEveryUnassignedDealThatNeedsSomeone', async ({page}) => {
  const loans = ['LA-1002', 'LA-1018', 'LA-1012', 'LA-1030'];          // RISC, GAP, income, payment work
  for (const loan of loans) await unassigned(loan);                     // the demo, or a person, may have taken one
  await signInAs(page, 'supervisor-1');
  for (const loan of loans) {
    await expect(card(ready(page), loan), loan).toBeVisible();
  }
  await expect(card(ready(page), 'LA-1030').locator('.work-chip')).toHaveText(['Payment outcome unknown']);
  await expect(card(ready(page), 'LA-1001')).toHaveCount(0);           // funded: nothing to do
});
