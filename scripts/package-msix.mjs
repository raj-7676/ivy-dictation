#!/usr/bin/env node
// Builds Ivy's Microsoft Store package (MSIX). Unlike the GitHub setup, the model goes INSIDE the package (the
// Store has no 2 GB file limit and installs it with the app, so there's no first-start download), along with the
// VC++ runtime DLLs the speech engine needs (setup.exe installs vc_redist; a package carries them itself).
// The Store signs the package; upload release/msix/Ivy-<version>.msix in Partner Center.
//
//   npm run package-msix            build the exe, stage, pack
//   npm run package-msix -- --skip-build
//
// To try the staged folder without packing or signing (needs Windows Developer Mode):
//   Add-AppxPackage -Register release\msix\Ivy-<version>\AppxManifest.xml
import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const msixDir = path.join(root, 'src-tauri', 'msix');
const version = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')).version;
// The Store wants four parts and a 0 revision.
const msixVersion = `${version}.0`;

const identityFile = path.join(msixDir, 'identity.json');
if (!existsSync(identityFile)) {
  console.error(`Missing ${identityFile}: {"name": "...", "publisher": "CN=...", "publisherDisplayName": "glow.co"} from Partner Center > Product identity.`);
  process.exit(1);
}
const identity = JSON.parse(readFileSync(identityFile, 'utf8'));

const liteDir = path.join(root, 'src-tauri', 'models', 'ivy-lite');
for (const f of ['ivy-lite-Q8_0.gguf', 'mmproj-ivy-lite-f16.gguf']) {
  if (!existsSync(path.join(liteDir, f))) {
    console.error(`Missing ${path.join(liteDir, f)}: run \`npm run setup-models\` first.`);
    process.exit(1);
  }
}

// Newest installed Windows SDK / VS Build Tools that has what we need.
const newest = (dir, test) =>
  existsSync(dir) ? readdirSync(dir).sort((a, b) => b.localeCompare(a, undefined, { numeric: true })).find((v) => test(path.join(dir, v))) : undefined;
const sdkBin = 'C:\\Program Files (x86)\\Windows Kits\\10\\bin';
const sdk = newest(sdkBin, (d) => existsSync(path.join(d, 'x64', 'makeappx.exe')));
if (!sdk) {
  console.error('makeappx.exe not found: install the Windows 10/11 SDK.');
  process.exit(1);
}
const makeappx = path.join(sdkBin, sdk, 'x64', 'makeappx.exe');
const makepri = path.join(sdkBin, sdk, 'x64', 'makepri.exe');
const redistRoot = ['C:\\Program Files (x86)\\Microsoft Visual Studio\\2022', 'C:\\Program Files\\Microsoft Visual Studio\\2022']
  .flatMap((vs) => (existsSync(vs) ? readdirSync(vs).map((ed) => path.join(vs, ed, 'VC', 'Redist', 'MSVC')) : []))
  .map((d) => { const v = newest(d, (p) => existsSync(path.join(p, 'x64', 'Microsoft.VC143.CRT'))); return v && path.join(d, v, 'x64'); })
  .find(Boolean);
if (!redistRoot) {
  console.error('VC++ redistributable DLLs not found: install Visual Studio 2022 Build Tools (C++).');
  process.exit(1);
}

if (!process.argv.includes('--skip-build')) {
  // Same exe as the setup build, without the NSIS bundle.
  execFileSync('npm run tauri build -- --no-bundle', { cwd: root, stdio: 'inherit', shell: true });
}
const targetDir = process.env.CARGO_TARGET_DIR || path.join(root, 'src-tauri', 'target');
const exe = path.join(targetDir, 'release', 'app.exe');
if (!existsSync(exe)) {
  console.error(`Missing ${exe}: the build didn't produce it.`);
  process.exit(1);
}

const out = path.join(root, 'release', 'msix');
const stage = path.join(out, `Ivy-${version}`);
rmSync(stage, { recursive: true, force: true });
mkdirSync(path.join(stage, 'Assets'), { recursive: true });

copyFileSync(exe, path.join(stage, 'Ivy.exe'));
cpSync(liteDir, path.join(stage, 'models', 'ivy-lite'), { recursive: true });
for (const dir of ['Microsoft.VC143.CRT', 'Microsoft.VC143.OpenMP']) {
  for (const dll of readdirSync(path.join(redistRoot, dir))) copyFileSync(path.join(redistRoot, dir, dll), path.join(stage, dll));
}
for (const icon of ['StoreLogo.png', 'Square44x44Logo.png', 'Square150x150Logo.png']) {
  copyFileSync(path.join(root, 'src-tauri', 'icons', icon), path.join(stage, 'Assets', icon));
}
// Taskbar/Start icons in every size, plus "unplated" ones: without those Windows draws a packaged app's icon on a
// coloured tile instead of transparently (Yash saw an orange square in the taskbar). Made from icons/icon.png.
cpSync(path.join(msixDir, 'Assets'), path.join(stage, 'Assets'), { recursive: true });
const manifest = readFileSync(path.join(msixDir, 'AppxManifest.xml'), 'utf8')
  .replaceAll('{{NAME}}', identity.name)
  .replaceAll('{{PUBLISHER}}', identity.publisher)
  .replaceAll('{{PUBLISHER_DISPLAY_NAME}}', identity.publisherDisplayName)
  .replaceAll('{{VERSION}}', msixVersion);
writeFileSync(path.join(stage, 'AppxManifest.xml'), manifest);
for (const license of ['LICENSE', 'THIRD_PARTY_NOTICES.md']) copyFileSync(path.join(root, license), path.join(stage, license));
cpSync(path.join(root, 'LICENSES'), path.join(stage, 'LICENSES'), { recursive: true });

// Windows only finds the size/unplated variants through a resources.pri index. Built from the manifest and Assets
// alone, so makepri doesn't walk the 2.4 GB model.
const pri = path.join(out, 'pri');
rmSync(pri, { recursive: true, force: true });
mkdirSync(pri, { recursive: true });
copyFileSync(path.join(stage, 'AppxManifest.xml'), path.join(pri, 'AppxManifest.xml'));
cpSync(path.join(stage, 'Assets'), path.join(pri, 'Assets'), { recursive: true });
const priConfig = path.join(out, 'priconfig.xml');
execFileSync(makepri, ['createconfig', '/cf', priConfig, '/dq', 'en-US', '/pv', '10.0.0', '/o'], { stdio: 'ignore' });
execFileSync(makepri, ['new', '/pr', pri, '/cf', priConfig, '/of', path.join(stage, 'resources.pri'), '/o'], { stdio: 'ignore' });
rmSync(pri, { recursive: true, force: true });
rmSync(priConfig, { force: true });

const msix = path.join(out, `Ivy-${version}.msix`);
console.log(`Packing ${msix} (the 2.4 GB model takes a few minutes)...`);
execFileSync(makeappx, ['pack', '/o', '/h', 'SHA256', '/d', stage, '/p', msix], { stdio: 'inherit' });
console.log(`\nDone: ${msix}\nUpload it in Partner Center > Ivy Dictation > submission > Packages.`);
