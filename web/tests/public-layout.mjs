import assert from 'node:assert/strict';
import { chromium, firefox, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { preview } from 'vite';
import { mockApi, summary } from './browser-fixtures.mjs';

const families = [
  { id: 1, man_name: 'Lin', woman_name: 'Yu', timing: '2026-10-01', bg_cover: null, man_cover: null, woman_cover: null, family_info: 'We keep the little moments.\nOne photo and one story at a time.', countdown_title: 'A special day', countdown_time: '2026-10-08' },
  { id: 2, man_name: 'Alex', woman_name: 'Sam', timing: '2026-09-01', bg_cover: null, man_cover: '/legacy/avatar.jpg', woman_cover: null, family_info: 'A different story, with the same wish to remember our days.', countdown_title: 'Our next trip', countdown_time: '2026-10-09' },
];
const photos = Array.from({ length: 14 }, (_, index) => ({ id: index + 1, title: `Memory ${index + 1}`, classify: index === 13 ? 'City' : 'Beach', cover: '/live/xocs-current-hero.jpg', create_time: '2026-10-08', introduction: null, url: null, link_type: 'lovePhoto' }));
const notes = Array.from({ length: 11 }, (_, index) => ({ id: index + 1, username: 'Lin', content: `Moment ${index + 1}: an ordinary day worth keeping.`, image_path: index === 0 ? '/legacy/avatar.jpg' : null, create_time: '2026-10-08', user_id: 1, is_public: 1 }));
const paged = (items, page, size) => ({ items: items.slice((page - 1) * size, page * size), page, size, total: items.length });

async function fixtures(page, state) {
  await mockApi(page, { authenticated: true });
  await page.route('**/api/v1/family', route => route.fulfill({ json: state.empty ? [] : families }));
  await page.route('**/api/v1/links/classes?*', route => route.fulfill({ json: state.empty ? [] : [{ classify: 'Beach', count: 13 }, { classify: 'City', count: 1 }] }));
  await page.route('**/api/v1/links/page?*', route => {
    if (state.failPhotos) return route.fulfill({ status: 503, json: { code: 'service_unavailable', retryable: true, request_id: 'public-layout-503' } });
    const query = new URL(route.request().url()).searchParams;
    const items = state.empty ? [] : photos.filter(item => !query.get('classify') || item.classify === query.get('classify'));
    return route.fulfill({ json: paged(items, Number(query.get('page') || 1), Number(query.get('size') || 12)) });
  });
  await page.route('**/api/v1/notes/page?*', route => {
    const query = new URL(route.request().url()).searchParams;
    return route.fulfill({ json: paged(state.empty ? [] : notes, Number(query.get('page') || 1), 10) });
  });
  await page.route('**/api/v1/articles?*', route => route.fulfill({ json: paged(Array.from({ length: 6 }, (_, index) => ({ ...summary, id: index + 1, article_title: `A day worth keeping ${index + 1}`, article_cover: '/live/xocs-current-hero.jpg' })), 1, 12) }));
}
async function widthCheck(page, wideSelector) {
  await page.evaluate(() => document.fonts.ready);
  const result = await page.evaluate(selector => ({ viewport: innerWidth, scroll: document.documentElement.scrollWidth,
    content: selector ? document.querySelector(selector)?.getBoundingClientRect().width : null }), wideSelector);
  assert.ok(result.scroll <= result.viewport, `${page.url()}: ${JSON.stringify(result)}`);
  if (result.viewport >= 1440 && wideSelector) assert.ok(result.content >= Math.min(result.viewport, 1920) * .87, `${wideSelector} is too narrow: ${JSON.stringify(result)}`);
}
async function accessible(page) {
  const { violations } = await new AxeBuilder({ page }).include('.love-page').withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  assert.deepEqual(violations.map(item => ({ id: item.id, nodes: item.nodes.map(node => node.target) })), [], page.url());
}

const server = await preview({ preview: { host: '127.0.0.1', port: 0, strictPort: true } });
const base = `http://127.0.0.1:${server.httpServer.address().port}`;
try {
  for (const engine of [chromium, firefox]) {
    const browser = await engine.launch();
    try {
      for (const width of [390, 1280, 1920, 2560]) {
        const context = await browser.newContext({ locale: 'en', timezoneId: 'Asia/Shanghai', viewport: { width, height: 1080 }, reducedMotion: 'reduce' });
        const page = await context.newPage(), state = { empty: false, failPhotos: false }, errors = [];
        page.on('pageerror', error => errors.push(error.message));
        await fixtures(page, state);
        await page.clock.install({ time: new Date('2026-10-08T12:34:56+08:00') });
        for (const [path, selector] of [['/', '.home-layout'], ['/sort', '.original-article-list'], ['/menory', '.journey-page'], ['/jotting', '.wall-layout'], ['/travel', '.travel-page'], ['/favorite', '.favorite-page'], ['/article/1', '.reading-layout'], ['/love', '.love-content']]) {
          await page.goto(`${base}${path}?lang=en`);
          await page.waitForLoadState('networkidle');
          await widthCheck(page, selector);
          assert.equal(await page.getByRole('alert').count(), 0, path);
        }
        await widthCheck(page, '.love-hero-inner');
        await expect(page.locator('.love-clock strong').first()).toHaveText('7');
        await expect(page.locator('.love-countdown')).toContainText('Today is the day');
        await expect(page.locator('.love-story-card')).toContainText(families[0].family_info);
        await expect(page.locator('.travel-photo-card')).toHaveCount(12);
        await expect(page.locator('.love-photo-filters').getByRole('button', { name: /^All/ })).toHaveText('All 14');
        const caption = await page.locator('.travel-photo-card').first().evaluate(card => ({
          imageBottom: card.querySelector('.travel-photo-image').getBoundingClientRect().bottom,
          titleTop: card.querySelector('strong').getBoundingClientRect().top,
          titleBottom: card.querySelector('strong').getBoundingClientRect().bottom,
          dateTop: card.querySelector('small').getBoundingClientRect().top,
        }));
        assert.ok(caption.titleTop >= caption.imageBottom && caption.dateTop >= caption.titleBottom, `Photo captions overlap: ${JSON.stringify(caption)}`);
        await page.getByRole('button', { name: 'Load more photos', exact: true }).click();
        await expect(page.locator('.travel-photo-card')).toHaveCount(14);
        await expect(page.getByRole('button', { name: 'Load more photos', exact: true })).toHaveCount(0);
        await page.locator('.love-photo-filters').getByRole('button', { name: /^City/ }).click();
        await expect(page.locator('.travel-photo-card')).toHaveCount(1);
        await page.getByRole('button', { name: 'View photo: Memory 14', exact: true }).click();
        await expect(page.getByRole('dialog', { name: 'View image', exact: true })).toBeVisible();
        await page.keyboard.press('Escape');
        await expect(page.getByRole('dialog', { name: 'View image', exact: true })).toHaveCount(0);
        if (width === 1920) await accessible(page);
        const tabs = page.locator('.love-tabs');
        await tabs.getByRole('button', { name: /^Moments/ }).click();
        await expect(page.locator('.love-moment-card')).toHaveCount(10);
        await page.getByRole('navigation', { name: 'Entry pages', exact: true }).getByRole('button', { name: 'Next page', exact: true }).click();
        await expect(page.locator('.love-moment-card')).toHaveCount(1);
        await expect(page.locator('.love-moments-grid')).toContainText('Moment 11');
        await tabs.getByRole('button', { name: /^Love wall/ }).click();
        await page.getByRole('button', { name: 'View the story of Alex and Sam', exact: true }).click();
        await expect(page.locator('.love-pair')).toContainText('Alex');
        await expect(page.locator('.love-story-card')).toContainText(families[1].family_info);
        await expect(page.locator('.love-countdown')).toContainText('1 day to go');
        await page.getByLabel('Choose a story', { exact: true }).selectOption('1');
        await expect(page.locator('.love-pair')).toContainText('Lin');
        await tabs.getByRole('button', { name: /^Wishes/ }).click();
        await expect(page.locator('.love-wishes').getByRole('heading', { name: /^Wishes/ })).toBeVisible();
        await expect(page.locator('.love-wishes').getByRole('button', { name: 'Post anonymous comment', exact: true })).toBeVisible();
        await widthCheck(page, '.love-content');
        if (width === 1920) await accessible(page);
        await page.evaluate(async () => {
          scrollTo(0, 0);
          await new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)));
        });
        await expect(page.locator('.public-header')).toBeVisible();
        await page.getByRole('button', { name: 'Switch to dark mode', exact: true }).click();
        await expect(page.locator('.original-public')).toHaveClass(/theme-dark/);
        await page.keyboard.press('Escape');
        await widthCheck(page, '.love-content');
        if (width === 1920) {
          await tabs.getByRole('button', { name: /^Photos/ }).click();
          await expect(page.locator('.travel-photo-card')).toHaveCount(1);
          await accessible(page);
          await tabs.getByRole('button', { name: /^Wishes/ }).click();
          await accessible(page);
        }
        state.empty = true;
        await page.reload();
        await expect(page.getByRole('heading', { name: 'No photos in this collection yet', exact: true })).toBeVisible();
        await expect(page.locator('.love-timer')).toContainText('Start with your first memory');
        await tabs.getByRole('button', { name: /^Love wall/ }).click();
        await expect(page.getByRole('heading', { name: 'No stories on the love wall yet', exact: true })).toBeVisible();
        await widthCheck(page, '.love-content');
        assert.deepEqual(errors, []);
        await context.close();
        console.log(`${engine.name()} ${width}px: wide layouts, photos, entries, stories, themes and empty states passed`);
      }
      const context = await browser.newContext({ locale: 'en', viewport: { width: 1280, height: 900 } }), page = await context.newPage(), state = { empty: false, failPhotos: true };
      await fixtures(page, state);
      await page.goto(`${base}/love?lang=en`);
      await expect(page.getByRole('alert')).toBeVisible();
      await expect(page.locator('.public-loading')).toHaveCount(0);
      state.failPhotos = false;
      await page.getByRole('button', { name: 'Try again', exact: true }).click();
      await expect(page.locator('.travel-photo-card')).toHaveCount(12);
      await expect(page.getByRole('alert')).toHaveCount(0);
      await context.close();
      console.log(`${engine.name()}: photo retry passed`);
    } finally { await browser.close(); }
  }
} finally { await new Promise(resolve => server.httpServer.close(resolve)); }
