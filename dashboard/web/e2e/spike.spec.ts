import {expect, test} from '@playwright/test';

// Spike D0 / T002: hand-written A2UI v1.0 messages render with the published React renderer,
// data bindings resolve, and a button click comes back as an action with its context resolved.
test('a v1.0 surface renders and its button sends an action', async ({page}) => {
  const errors: string[] = [];
  page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
  page.on('pageerror', (e) => errors.push(e.message));

  await page.goto('/');
  await expect(page.getByText('LA-1006').first()).toBeVisible();
  await expect(page.getByText('Status: STIPULATED')).toBeVisible();      // formatString with ${/deal/status}
  await expect(page.getByText('Lone Star Motors')).toBeVisible();        // {"@path": ...}

  await page.getByRole('button', {name: 'Take this deal'}).click();
  const events = page.getByTestId('events');
  await expect(events).toContainText('deal.take');
  await expect(events).toContainText('LA-1006');                         // context bound with @path, resolved by the renderer
  console.log('EVENT:', await events.textContent());
  expect(errors, errors.join('\n')).toEqual([]);
});
