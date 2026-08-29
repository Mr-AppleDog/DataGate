import { chromium } from "playwright";
(async () => {
  const browser = await chromium.launch({ headless: true });
  const page = await browser.newPage();
  await page.goto('http://127.0.0.1/', { waitUntil: 'domcontentloaded', timeout: 20000 });
  const title = await page.title();
  const url = page.url();
  const bodyText = await page.textContent('body');
  const hasPwd = (await page.$('input[type="password"]')) !== null;
  const hasUser = (await page.$('input[type="text"], input[placeholder*="用户名"], input[placeholder*="user"], input[type="email"]')) !== null;
  const hasBtn = (await page.$('button')) !== null;
  const hasLoginWord = (await page.content()).includes('登录');
  const marker = {
    title,
    url,
    hasPwd,
    hasUser,
    hasBtn,
    hasLoginWord,
    bodySnippet: (bodyText || '').slice(0, 260)
  };
  console.log(JSON.stringify(marker));
  await page.screenshot({ path: 'E:/github-reposit/DataGate/tmp-home.png', fullPage: true });
  await browser.close();
})();
