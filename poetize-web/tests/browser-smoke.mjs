import { chromium } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const dist = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist');
const base = process.env.POETIZE_SMOKE_URL || 'http://poetize.test';
const browser = await chromium.launch({ headless: true, args: ['--no-sandbox'] });
try {
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  if (!process.env.POETIZE_SMOKE_URL) await page.route('http://poetize.test/**', async route => {
    const url = new URL(route.request().url());
    if (url.pathname.startsWith('/api/')) {
      const payload = url.pathname === '/api/v2/site'
        ? { web_name: 'POETIZE', web_title: '测试站点', notices: null, footer: null, background_image: null, avatar: null }
        : url.pathname === '/api/v2/home-sections' ? [{id:1,title:'最新',kind:'latest',sort_id:null,priority:0,enabled:true}]
        : url.pathname === '/api/v2/categories' ? [{id:1,sort_name:'测试分类',sort_description:null,priority:0,article_count:0}]
        : url.pathname === '/api/v2/articles' ? { items: [], total: 0, page: 1, size: 12 }
        : ['/api/v2/family', '/api/v2/notes', '/api/v2/tree-hole', '/api/v2/links'].includes(url.pathname) ? []
        : { error: 'unauthorized' };
      await route.fulfill({ status: 'error' in payload ? 401 : 200, contentType: 'application/json', body: JSON.stringify(payload) });
      return;
    }
    const asset = url.pathname.startsWith('/assets/') || /\.(png|svg)$/.test(url.pathname);
    const target = asset ? path.join(dist, url.pathname) : path.join(dist, 'index.html');
    const contentType = target.endsWith('.js') ? 'application/javascript' : target.endsWith('.css') ? 'text/css' : target.endsWith('.woff2') ? 'font/woff2' : target.endsWith('.png') ? 'image/png' : target.endsWith('.svg') ? 'image/svg+xml' : 'text/html';
    await route.fulfill({ status: 200, contentType, body: await readFile(target) });
  });
  const homeHeading = process.env.POETIZE_SMOKE_URL ? '相信记录的力量' : '测试站点';
  for (const [route, heading] of [['/', homeHeading], ['/search', '搜索文章'], ['/sort', '文章分类'], ['/weiYan', '微言'], ['/jotting', 'POETIZE'], ['/menory', 'POETIZE'], ['/favorite', '百宝箱'], ['/friend', '友人帐'], ['/music', '音乐盒'], ['/travel', '时光相册'], ['/love', '这是我们一起走过的'], ['/message', '弹幕'], ['/user', '我的资料']]) {
    await page.goto(`${base}${route}`);
    await page.getByRole('heading', { name: heading }).first().waitFor();
    console.log(`${route}: rendered`);
  }
  if (!process.env.POETIZE_SMOKE_URL) {
    await page.goto(`${base}/`);
    await page.locator('.public-sort-menu').filter({hasText:'记录'}).locator('summary').click();
    await page.getByRole('link', {name: '测试分类 0'}).click();
    await page.getByRole('heading', {name: '测试分类'}).first().waitFor();
  }
  await page.goto(`${base}/love`);
  await page.getByRole('button', {name: /表白墙/}).click();
  await page.getByRole('button', {name: /申请入住/}).click();
  await page.getByRole('dialog', {name: '入住表白墙'}).getByRole('link', {name: '登录后申请入住'}).waitFor();
  await page.goto(`${base}/letter`);
  await page.getByRole('button', { name: '打开信封' }).click();
  await page.getByRole('heading', { name: 'To Ming' }).waitFor();
  await page.goto(`${base}/about`);
  await page.getByRole('button', { name: '然后呢？ 😃' }).click();
  await page.getByText('本站平时用于交流、分享和学习新知识。').waitFor();
  if (process.env.POETIZE_SMOKE_URL) {
    await page.goto(`${base}/love`);
    await page.getByRole('button', { name: /祝福板/ }).click();
    await page.getByText('Smoke love wish').waitFor();
    await page.goto(`${base}/weiYan`);
    await page.getByText('Smoke author note').waitFor();
    await page.goto(`${base}/`);
    await page.getByRole('heading', { name: '专栏速览' }).waitFor();
    await page.locator('.aside-recommended a').waitFor();
    await page.locator('.home-section[aria-label="专栏速览"] .section-heading a').click();
    if (!page.url().includes('sort_id=1')) throw new Error('Category section did not open its listing');
    await page.getByRole('heading', { name: 'Smoke' }).first().waitFor();
    await page.goto(`${base}/article/1`);
    await page.getByRole('heading', { name: 'Smoke' }).waitFor();
    await page.getByText('Smoke article news').waitFor();
    await page.getByRole('link', { name: 'Chapter One' }).first().click();
    if (!page.url().endsWith('#section-1')) throw new Error('Article table of contents did not navigate');
    await page.getByRole('progressbar', { name: '阅读进度' }).waitFor();
    console.log('/article/1: rendered');
    await page.goto(`${base}/search?q=Chapter`);
    await page.getByRole('heading', { name: '搜索文章' }).waitFor();
    await page.getByText('正文匹配').waitFor();
    await page.locator('.search-snippet mark').first().waitFor();
    await page.goto(`${base}/message`);
    await page.getByText('图片留言').waitFor();
    await page.getByRole('textbox', { name: '发送弹幕' }).fill('访客弹幕测试');
    await page.getByRole('button', { name: '发射' }).click();
    await page.getByText('访客弹幕测试').waitFor();
    const mobile = await browser.newPage({ viewport: { width: 390, height: 844 } });
    mobile.on('pageerror', error => errors.push(error.message));
    await mobile.goto(`${base}/im`);
    await mobile.locator('.community-login input').first().fill('smokemember');
    await mobile.locator('.community-login input[type="password"]').fill('MemberPassphrase123');
    await mobile.locator('.community-login button[type="submit"]').click();
    await mobile.locator('.community-member strong').getByText('smokemember').waitFor();
    const pixel = { name: 'pixel.png', mimeType: 'image/png', buffer: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/8ZkAAAAASUVORK5CYII=', 'base64') };
    await mobile.goto(`${base}/weiYan`);
    await mobile.getByText('图片动态').waitFor();
    await mobile.locator('.wall-compose textarea').fill('手机端发布图片');
    await mobile.locator('.wall-compose input[type="file"]').setInputFiles(pixel);
    await mobile.getByRole('img', { name: '待发布图片预览' }).waitFor();
    await mobile.locator('.wall-compose button[type="submit"]').click();
    await mobile.getByText('手机端发布图片').waitFor();
    await mobile.getByRole('button', { name: '放大图片' }).first().click();
    await mobile.getByRole('dialog', { name: '查看图片' }).waitFor();
    await mobile.keyboard.press('Escape');
    await mobile.goto(`${base}/message`);
    await mobile.getByRole('textbox', { name: '发送弹幕' }).fill('手机端留言');
    await mobile.getByRole('button', { name: '发射' }).click();
    await mobile.getByText('手机端留言').waitFor();
    if (await mobile.evaluate(() => document.documentElement.scrollWidth > window.innerWidth + 2)) throw new Error('Mobile wall overflows viewport');
    await mobile.close();
    await page.goto(`${base}/article/3`);
    await page.getByRole('heading', { name: '这篇文章需要访问密码' }).waitFor();
    await page.locator('input[type="password"]').fill('ProtectedPassphrase123');
    await page.locator('form button[type="submit"]').click();
    await page.getByRole('heading', { name: 'Protected Preview' }).waitFor();
    console.log('/article/3: unlocked');
  }
  await page.goto(`${base}/admin`);
  await page.locator('body').getByText('POETIZE').first().waitFor();
  if (process.env.POETIZE_SMOKE_URL) {
    await page.locator('input[name="username"]').fill('admin');
    await page.locator('input[name="password"]').fill('TemporaryPassphrase123');
    await page.locator('form button[type="submit"]').click();
    await page.getByRole('heading', { name: '文章管理' }).waitFor();
    for (const label of ['总览', '首页栏目', '分类管理', '标签管理', '评论管理', '动态管理', '留言板管理', '用户管理', '文件管理', '内容资源', '恋爱笔记', '网站设置']) {
      await page.getByRole('link', { name: label, exact: true }).first().click();
      await page.getByRole('heading', { name: label }).waitFor();
      if (label === '首页栏目') {
        await page.locator('table tbody tr').first().waitFor();
        if (await page.locator('table tbody tr').count() !== 3) throw new Error('Home section editor did not load all sections');
        await page.getByRole('button', { name: '上移栏目 3' }).click();
        const saved = page.waitForResponse(response => response.url().endsWith('/api/v2/content/home-sections') && response.request().method() === 'PUT');
        await page.getByRole('button', { name: '保存栏目' }).click();
        if (!(await saved).ok()) throw new Error('Home section reorder failed');
        const order = await page.evaluate(async () => (await (await fetch('/api/v2/home-sections')).json()).map(section => section.title));
        if (order[1] !== '专栏速览') throw new Error('Home section order was not persisted');
      }
    }
    if (process.env.POETIZE_SMOKE_SCREENSHOT) await page.screenshot({ path: process.env.POETIZE_SMOKE_SCREENSHOT, fullPage: true });
    console.log('/admin: signed in and navigated');
  }
  console.log('/admin: rendered');
  if (errors.length) throw new Error(`Browser errors: ${errors.join('; ')}`);
} finally {
  await browser.close();
}
