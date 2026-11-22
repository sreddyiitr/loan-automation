import {expect, test, type Page} from '@playwright/test';
import {api, signInAs, unassigned, watchErrors} from './support';

// Spec 002 D2b (US-12): the supervisor asks the assistant who should take each unassigned deal, reads
// why, and confirms one suggestion at a time. The agent replays a recorded answer (AS-10.5).

const panel = (page: Page) => page.locator('.workspace-side').filter({hasText: 'Assignment suggestions'});
const row = (page: Page, loan: string) => panel(page).locator(`.suggestion[data-loan="${loan}"]`);
const suggest = (page: Page) => page.getByRole('button', {name: 'Suggest assignments'});

test('AS_12_1_theSupervisorGetsASuggestionForEachUnassignedDealWithItsReason', async ({page}) => {
  const errors = watchErrors(page);
  await signInAs(page, 'supervisor-1');
  await suggest(page).click();
  await expect(panel(page)).toContainText('Suggested by the assistant');
  const rows = panel(page).locator('.suggestion');
  await expect(rows.first()).toBeVisible();
  expect(await rows.count()).toBeGreaterThan(3);
  // an income deal goes to the income specialist, and every row says why
  await expect(row(page, 'LA-1012').locator('.suggestion-assignee')).toHaveText('income-1');
  for (const reason of await panel(page).locator('.suggestion-reason').allInnerTexts()) expect(reason.trim()).not.toEqual('');
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_12_4_nothingIsAssignedUntilTheSupervisorConfirmsThatOneSuggestion', async ({page}) => {
  const errors = watchErrors(page);
  const v = await unassigned('LA-1027');                                // income work no other test touches
  await signInAs(page, 'supervisor-1');
  await suggest(page).click();
  const la1027 = row(page, 'LA-1027');
  await expect(la1027.locator('.suggestion-assignee')).toHaveText('income-1');

  // Assign asks first; Cancel leaves the deal unassigned
  await la1027.getByRole('button', {name: 'Assign LA-1027 to income-1'}).click();
  await expect(la1027).toContainText('Assign LA-1027 to income-1?');
  await la1027.getByRole('button', {name: 'Cancel'}).click();
  const before = await (await api('supervisor-1', '/applications/LA-1027')).json();
  expect(before.assignedFunder ?? null).toBeNull();

  // confirmed: the normal audited reassign, and only this deal
  await la1027.getByRole('button', {name: 'Assign LA-1027 to income-1'}).click();
  await la1027.getByRole('button', {name: 'Yes, assign'}).click();
  await expect(la1027).toContainText('Assigned ✓');
  await expect(page.getByText('LA-1027 is assigned to income-1.')).toBeVisible();
  const after = await (await api('supervisor-1', '/applications/LA-1027')).json();
  expect(after.assignedFunder).toBe('income-1');
  expect(after.applicationVersionId).toBe(v.applicationVersionId);
  await expect(row(page, 'LA-1012').locator('.suggestion-sent')).toHaveCount(0);   // the others are untouched
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_12_6_aSpecialistIsNotOfferedSuggestions', async ({page}) => {
  await signInAs(page, 'income-1');
  await expect(page.locator('.work-list').first()).toBeVisible();
  await expect(suggest(page)).toHaveCount(0);
});
