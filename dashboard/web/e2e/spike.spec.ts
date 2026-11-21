import {expect, test} from '@playwright/test';

// Spike D0 / T001-T004: the agent sends A2UI v1.0 over A2A, the React renderer draws it, a click
// goes back to the agent as an action, and code (no LLM) answers with a data-model update.
// Needs the agent running on :8091 (`uv run python main.py` in dashboard/agent).
test('a v1.0 surface renders and its button sends an action', async ({page}) => {
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));

  await page.goto('/');
  await expect(page.getByText('LA-1006').first()).toBeVisible();
  await expect(page.getByText('Status: STIPULATED')).toBeVisible();      // formatString with ${/deal/status}
  await expect(page.getByText('Lone Star Motors')).toBeVisible();        // {"@path": ...}
  await expect(page.getByText('Assigned to: nobody')).toBeVisible();

  await page.getByRole('button', {name: 'Take this deal'}).click();
  const events = page.getByTestId('events');
  await expect(events).toContainText('deal.take');
  await expect(events).toContainText('LA-1006');                         // context bound with @path, resolved by the renderer
  console.log('EVENT:', await events.textContent());
  await expect(page.getByText('Assigned to: you (spike)')).toBeVisible();   // the agent's reply was applied
  expect(errors, errors.join('\n')).toEqual([]);
});
