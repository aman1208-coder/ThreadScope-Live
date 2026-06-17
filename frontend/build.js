const fs = require('fs');
const path = require('path');

const srcDir = __dirname;
const outDir = path.join(srcDir, 'dist');
const indexPath = path.join(srcDir, 'index.html');
const configPath = path.join(outDir, 'env-config.js');

function copyRecursive(source, destination) {
  const stat = fs.statSync(source);
  if (stat.isDirectory()) {
    fs.mkdirSync(destination, { recursive: true });
    for (const entry of fs.readdirSync(source)) {
      copyRecursive(path.join(source, entry), path.join(destination, entry));
    }
  } else {
    fs.copyFileSync(source, destination);
  }
}

if (fs.existsSync(outDir)) {
  fs.rmSync(outDir, { recursive: true, force: true });
}
fs.mkdirSync(outDir, { recursive: true });

const html = fs.readFileSync(indexPath, 'utf8');
const apiUrl = process.env.API_URL || process.env.NEXT_PUBLIC_API_URL || 'https://threadscope-live.onrender.com/api';
const wsUrl = process.env.WS_URL || process.env.NEXT_PUBLIC_WS_URL || 'wss://threadscope-live.onrender.com/ws/metrics';

const config = {
  API_URL: apiUrl,
  WS_URL: wsUrl
};

const envConfigJs = `window.APP_CONFIG = ${JSON.stringify(config, null, 2)};\n`;

fs.writeFileSync(path.join(outDir, 'index.html'), html, 'utf8');
fs.writeFileSync(configPath, envConfigJs, 'utf8');

for (const entry of fs.readdirSync(srcDir)) {
  if (['build.js', 'package.json', 'package-lock.json', 'dist', 'node_modules'].includes(entry)) {
    continue;
  }

  const sourcePath = path.join(srcDir, entry);
  const destinationPath = path.join(outDir, entry);
  copyRecursive(sourcePath, destinationPath);
}

console.log('Built frontend to', outDir);
