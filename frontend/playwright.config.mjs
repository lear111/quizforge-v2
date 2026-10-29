import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir:'./test/browser',outputDir:'../.artifacts/frontend-browser',timeout:45000,workers:1,
  use:{baseURL:process.env.QF_BROWSER_BASE_URL||'http://localhost:8088',channel:'chromium',trace:'retain-on-failure'},
  projects:[{name:'desktop',use:{viewport:{width:1440,height:960}}},{name:'mobile',use:{viewport:{width:375,height:812},isMobile:true,hasTouch:true}}],
});
