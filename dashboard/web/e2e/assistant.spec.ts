import {readFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {expect, test} from '@playwright/test';
import {fact, openDeal, signInAs, watchErrors} from './support';

// Spec 002 D1b: "Summarize" on a deal (AS-8.1) and the assistant unavailable (AS-8.4), through the
// real browser, agent and funder API. The agent replays recorded answers of the real model
// (ASSISTANT_REPLAY, playwright.config.ts), so no model runs (AS-10.5). A deal with no recording
// behaves as if no model is running. That "Summarizing…" comes before the answer is checked on
// the agent, over message/stream (test_assistant_panel.py): a replayed answer arrives too fast to see.
const RECORDINGS = fileURLToPath(new URL('../../agent/recordings/summary/', import.meta.url));
const recorded = (loan: string) => JSON.parse(readFileSync(`${RECORDINGS}${loan}.json`, 'utf8')).summary as string;

test('AS_8_1_summarizeExplainsTheDealReadOnlyAndTheOverviewStays', async ({page}) => {
  const errors = watchErrors(page);
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1006');
  await expect(page.getByText(/^LA-1006 · v\d+$/)).toBeVisible();

  await page.getByRole('button', {name: 'Summarize'}).click();
  await expect(page.getByText('Assistant summary')).toBeVisible();
  await expect(page.getByText(recorded('LA-1006'), {exact: true})).toBeVisible();   // the model's words, as plain text
  await expect(page.getByText(/^Written by the assistant from this deal's data only\./)).toBeVisible();
  await expect(page.getByText('Summarizing…')).toHaveCount(0);

  const panel = page.locator('div.a2ui-card').filter({hasText: 'Assistant summary'});
  await expect(panel).toHaveCount(1);                                              // found, so the next line means something
  await expect(panel.getByRole('button')).toHaveCount(0);                          // read-only: no actions
  const overview = page.locator('div.a2ui-card').filter({hasText: 'Version history'});
  await expect(overview.getByRole('button', {name: 'Summarize'})).toHaveCount(1);  // the button lives on the overview
  await expect(page.getByText(/^LA-1006 · v\d+$/)).toBeVisible();                 // the overview is still there
  await expect(fact(page, 'Assigned to')).toBeVisible();
  await expect(page.getByTestId('events')).toContainText('deal.summarize');

  await page.getByRole('button', {name: 'Summarize'}).click();                    // again: one panel, rebuilt
  await expect(page.getByText('Assistant summary')).toHaveCount(1);
  await expect(page.getByText(recorded('LA-1006'), {exact: true})).toHaveCount(1);
  expect(errors, errors.join('\n')).toEqual([]);
});

test('AS_8_1_aFinishedDealIsSummarizedByHowItEnded', async ({page}) => {
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1001');
  await page.getByRole('button', {name: 'Summarize'}).click();
  await expect(page.getByText(recorded('LA-1001'), {exact: true})).toBeVisible();
  expect(recorded('LA-1001')).toMatch(/FUNDED/);
});

test('AS_8_4_withNoModelTheAssistantIsUnavailableAndEverythingElseWorks', async ({page}) => {
  const errors = watchErrors(page);
  await signInAs(page, 'funder-1');
  await openDeal(page, 'LA-1002');                                                 // no recording: as if no model runs
  await page.getByRole('button', {name: 'Summarize'}).click();
  await expect(page.getByText('The assistant is unavailable right now. Everything else on this deal works.')).toBeVisible();
  await expect(page.getByText(/^Written by the assistant/)).toHaveCount(0);

  await expect(page.getByText(/^LA-1002 · v\d+$/)).toBeVisible();                 // the overview still works
  await expect(fact(page, 'Dealer')).toHaveText('Lone Star Motors');
  await expect(page.getByRole('button', {name: 'Open the workflow in Temporal'})).toBeVisible();
  await openDeal(page, 'LA-1006');                                                // and so does opening another deal
  await expect(page.getByText(/^LA-1006 · v\d+$/)).toBeVisible();
  expect(errors, errors.join('\n')).toEqual([]);
});
