const {defineConfig,devices}=require('@playwright/test');
module.exports=defineConfig({testDir:'./tests',timeout:45000,retries:process.env.CI?1:0,
 use:{baseURL:process.env.BASE_URL||'http://127.0.0.1:8080',trace:'retain-on-failure',launchOptions:process.env.CHROMIUM_PATH?{executablePath:process.env.CHROMIUM_PATH}:{}},
 projects:[{name:'chromium',use:{...devices['Desktop Chrome']}},{name:'mobile',use:{...devices['iPhone 13'],defaultBrowserType:'chromium'}}]});
