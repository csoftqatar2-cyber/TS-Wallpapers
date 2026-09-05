@echo off
rem Publishes the current local panel to https://thabthaba-programs-admin.tsdash-qatar.workers.dev
rem 1) copies the page from the local runner folder (source of truth), 2) deploys the Worker.
rem Secrets are NOT touched here; set them once with:
rem   npx wrangler secret put STORE_ADMIN_SECRET | TSLINK_ADMIN_TOKEN | LEO_ADMIN_TOKEN | CONTROLLER_ADMIN_SECRET
cd /d "%~dp0"
copy /y "%USERPROFILE%\.ts-secrets\_local-dashboard\control-panel.html" control-panel.html >nul
npx wrangler deploy
