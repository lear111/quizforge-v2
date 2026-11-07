import {defineConfig} from '@playwright/test';
// QF_BROWSER_IGNORE_HTTPS_ERRORS=1：对本机自签证书的生产配置演练运行（docs/ops/01-deploy.md 第 4 节）。
export default defineConfig({
  testDir:'./test/browser',outputDir:'../.artifacts/frontend-browser',timeout:45000,workers:1,
  use:{baseURL:process.env.QF_BROWSER_BASE_URL||'http://localhost:8088',channel:'chromium',trace:'retain-on-failure',...(process.env.QF_BROWSER_IGNORE_HTTPS_ERRORS?{ignoreHTTPSErrors:true}:{}),...(process.env.QF_AUDIO_TEST_URL?{ignoreHTTPSErrors:true,launchOptions:{args:['--host-resolver-rules=MAP audio.qf.test 127.0.0.1','--no-proxy-server']}}:{})},
  projects:[{name:'desktop',use:{viewport:{width:1440,height:960}}},{name:'mobile',use:{viewport:{width:375,height:812},isMobile:true,hasTouch:true}}],
});
