const fs = require('fs');
const path = require('path');

const srcDir = __dirname;
const outDir = path.join(srcDir, 'dist');
const indexPath = path.join(srcDir, 'index.html');
const configPath = path.join(outDir, 'env-config.js');

if (!fs.existsSync(outDir)) {
  fs.mkdirSync(outDir, { recursive: true });
}

const html = fs.readFileSync(indexPath, 'utf8');
const apiUrl = process.env.API_URL || process.env.NEXT_PUBLIC_API_URL || 'http://localhost:8080/api';
const wsUrl = process.env.WS_URL || process.env.NEXT_PUBLIC_WS_URL || 'ws://localhost:8080/ws/metrics';

const config = {
  API_URL: apiUrl,
  WS_URL: wsUrl
};

const envConfigJs = `window.APP_CONFIG = ${JSON.stringify(config, null, 2)};\n`;

fs.writeFileSync(path.join(outDir, 'index.html'), html, 'utf8');
fs.writeFileSync(configPath, envConfigJs, 'utf8');
console.log('Built frontend to', outDir);
