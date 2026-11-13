// Minimal Expo Updates protocol v1 server for local reproduction.
// No code signing, no dependencies. Serves the newest directory under
// updates/<runtimeVersion>/ that contains a metadata.json produced by `expo export`.
import { createHash } from 'node:crypto';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const PORT = Number(process.env.PORT ?? 3000);
const HOSTNAME = process.env.UPDATES_HOST ?? 'http://10.0.2.2:3000';
const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'updates');

const sha256Base64Url = (buf) => createHash('sha256').update(buf).digest('base64url');
const md5 = (buf) => createHash('md5').update(buf).digest('hex');

function uuidFromHash(hex) {
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20, 32)].join('-');
}

function latestUpdateDir(runtimeVersion) {
  const dir = path.join(ROOT, runtimeVersion);
  if (!existsSync(dir)) return null;
  const candidates = readdirSync(dir)
    .filter((name) => existsSync(path.join(dir, name, 'metadata.json')))
    .sort();
  return candidates.length ? path.join(dir, candidates.at(-1)) : null;
}

function assetEntry(updateDir, relPath, ext, isLaunchAsset) {
  const buf = readFileSync(path.join(updateDir, relPath));
  const rel = path.relative(ROOT, path.join(updateDir, relPath));
  return {
    hash: sha256Base64Url(buf),
    key: md5(buf),
    fileExtension: `.${ext}`,
    contentType: isLaunchAsset ? 'application/javascript' : contentTypeFor(ext),
    url: `${HOSTNAME}/api/assets?path=${encodeURIComponent(rel)}`,
  };
}

function contentTypeFor(ext) {
  return { png: 'image/png', jpg: 'image/jpeg', ttf: 'font/ttf', js: 'application/javascript', hbc: 'application/javascript' }[ext] ?? 'application/octet-stream';
}

function buildManifest(updateDir, runtimeVersion, platform) {
  const metadataBuf = readFileSync(path.join(updateDir, 'metadata.json'));
  const metadata = JSON.parse(metadataBuf);
  const published = JSON.parse(readFileSync(path.join(updateDir, 'published.json'), 'utf8'));
  const expoConfig = JSON.parse(readFileSync(path.join(updateDir, 'expoConfig.json'), 'utf8'));
  const platformMeta = metadata.fileMetadata[platform];
  return {
    id: uuidFromHash(createHash('sha256').update(metadataBuf).digest('hex')),
    createdAt: published.createdAt,
    runtimeVersion,
    launchAsset: assetEntry(updateDir, platformMeta.bundle, 'bundle', true),
    assets: platformMeta.assets.map((a) => assetEntry(updateDir, a.path, a.ext, false)),
    metadata: {},
    extra: { expoClient: expoConfig },
  };
}

function sendMultipart(res, name, body) {
  const boundary = `boundary-${Date.now().toString(16)}`;
  const payload =
    `--${boundary}\r\n` +
    `Content-Type: application/json; charset=utf-8\r\n` +
    `Content-Disposition: form-data; name="${name}"\r\n\r\n` +
    `${JSON.stringify(body)}\r\n` +
    `--${boundary}--\r\n`;
  res.writeHead(200, {
    'content-type': `multipart/mixed; boundary=${boundary}`,
    'expo-protocol-version': '1',
    'expo-sfv-version': '0',
    'cache-control': 'private, max-age=0',
  });
  res.end(payload);
}

const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  try {
    if (url.pathname === '/api/manifest') {
      const platform = req.headers['expo-platform'] ?? url.searchParams.get('platform');
      const runtimeVersion = req.headers['expo-runtime-version'] ?? url.searchParams.get('runtime-version');
      const currentId = req.headers['expo-current-update-id'];
      const dir = runtimeVersion && latestUpdateDir(runtimeVersion);
      if (!dir) {
        console.log(`manifest: rv=${runtimeVersion} current=${currentId} -> noUpdateAvailable (nothing published)`);
        return sendMultipart(res, 'directive', { type: 'noUpdateAvailable' });
      }
      const manifest = buildManifest(dir, runtimeVersion, platform);
      if (currentId === manifest.id) {
        console.log(`manifest: rv=${runtimeVersion} current=${currentId} -> noUpdateAvailable (already on latest)`);
        return sendMultipart(res, 'directive', { type: 'noUpdateAvailable' });
      }
      console.log(`manifest: rv=${runtimeVersion} current=${currentId} -> ${manifest.id} (${path.basename(dir)})`);
      return sendMultipart(res, 'manifest', manifest);
    }
    if (url.pathname === '/api/assets') {
      const file = path.resolve(ROOT, url.searchParams.get('path') ?? '');
      if (!file.startsWith(ROOT + path.sep) || !existsSync(file)) {
        res.writeHead(404).end();
        return;
      }
      const ext = path.extname(file).slice(1);
      res.writeHead(200, { 'content-type': contentTypeFor(ext) });
      res.end(readFileSync(file));
      console.log(`asset: ${path.relative(ROOT, file)}`);
      return;
    }
    res.writeHead(404).end();
  } catch (error) {
    console.error(error);
    res.writeHead(500).end(String(error));
  }
});

server.listen(PORT, () => console.log(`Expo Updates test server on :${PORT}, asset host ${HOSTNAME}`));
