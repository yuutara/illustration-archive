// Renders packaging/icon/app-icon.png, app-icon-small.svg and splash.html into the .ico and splash PNGs that
// build-desktop.ps1 packages, and copies the small artwork to the pages as their tab icon.
// Run by hand after changing the artwork: node scripts/build-icons.mjs. The outputs are committed, so a normal
// build needs neither this script nor Edge. It only loads local files.
import { spawn } from 'node:child_process';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ICON_DIR = join(ROOT, 'packaging/icon');
const WINDOW_ICON_DIR = join(ROOT, 'src/main/resources/desktop');
const EDGE = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe';
// 24, 30, 36 and 48 are the taskbar at 100%, 125%, 150% and 200% display scale; Windows blurs anything it has to resize.
const ICON_SIZES = [16, 20, 24, 30, 32, 36, 40, 48, 64, 72, 96, 128, 256];
// What the window itself shows in its title bar and on the taskbar; DesktopWindow loads these.
const WINDOW_ICON_SIZES = [16, 20, 24, 30, 32, 36, 40, 48, 64, 256];
const SMALL_ARTWORK_UP_TO = 20;
// The java launcher picks splash@125pct.png and so on by the display's scale.
const SPLASH_SCALES = [[1, 'splash.png'], [1.25, 'splash@125pct.png'], [1.5, 'splash@150pct.png'], [2, 'splash@200pct.png']];
// The 460 x 280 card plus 24 px of transparent margin for its shadow.
const SPLASH_SIZE = { width: 508, height: 328 };

const profile = await mkdtemp(join(tmpdir(), 'ia-icons-'));
const edge = spawn(EDGE, ['--headless=new', `--user-data-dir=${profile}`, '--remote-debugging-port=0', '--no-first-run', 'about:blank'], { stdio: 'ignore' });

async function devToolsPort() {
    for (let attempt = 0; attempt < 100; attempt++) {
        await new Promise((done) => setTimeout(done, 200));
        const port = await readFile(join(profile, 'DevToolsActivePort'), 'utf8').catch(() => null);
        if (port) return port.split('\n')[0].trim();
    }
    throw new Error('Edge did not open its debugging port.');
}

const targets = await (await fetch(`http://127.0.0.1:${await devToolsPort()}/json`)).json();
const socket = new WebSocket(targets.find((target) => target.type === 'page').webSocketDebuggerUrl);
await new Promise((done) => { socket.onopen = done; });
let nextId = 0;
const pending = new Map();
socket.onmessage = (message) => {
    const reply = JSON.parse(message.data);
    if (reply.id) { pending.get(reply.id)(reply.result); pending.delete(reply.id); }
};
const send = (method, params = {}) => new Promise((done) => {
    const id = ++nextId;
    pending.set(id, done);
    socket.send(JSON.stringify({ id, method, params }));
});

async function capture(url, width, height, scale) {
    await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: scale, mobile: false });
    await send('Emulation.setDefaultBackgroundColorOverride', { color: { r: 0, g: 0, b: 0, a: 0 } });
    await send('Page.navigate', { url });
    await new Promise((done) => setTimeout(done, 400));
    const { data } = await send('Page.captureScreenshot', { format: 'png', clip: { x: 0, y: 0, width, height, scale: 1 } });
    return Buffer.from(data, 'base64');
}

// An .ico is a directory of entries followed by the images; Windows reads PNG entries at every size.
function ico(images) {
    const header = Buffer.alloc(6 + 16 * images.length);
    header.writeUInt16LE(1, 2);
    header.writeUInt16LE(images.length, 4);
    let offset = header.length;
    images.forEach(({ size, png }, index) => {
        const entry = 6 + 16 * index;
        header.writeUInt8(size === 256 ? 0 : size, entry);
        header.writeUInt8(size === 256 ? 0 : size, entry + 1);
        header.writeUInt16LE(1, entry + 4);
        header.writeUInt16LE(32, entry + 6);
        header.writeUInt32LE(png.length, entry + 8);
        header.writeUInt32LE(offset, entry + 12);
        offset += png.length;
    });
    return Buffer.concat([header, ...images.map(({ png }) => png)]);
}

try {
    await send('Page.enable');
    const images = [];
    for (const size of ICON_SIZES) {
        const artwork = size <= SMALL_ARTWORK_UP_TO ? 'app-icon-small.svg' : 'app-icon.png';
        const page = `data:text/html,<body style="margin:0"><img src="${pathToFileURL(join(ICON_DIR, artwork))}" width="${size}" height="${size}" style="display:block">`;
        await writeFile(join(profile, 'page.html'), decodeURIComponent(page.slice('data:text/html,'.length)));
        images.push({ size, png: await capture(pathToFileURL(join(profile, 'page.html')).href, size, size, 1) });
    }
    await writeFile(join(ICON_DIR, 'app.ico'), ico(images));
    await mkdir(WINDOW_ICON_DIR, { recursive: true });
    for (const { size, png } of images.filter(({ size }) => WINDOW_ICON_SIZES.includes(size))) {
        await writeFile(join(WINDOW_ICON_DIR, `icon-${size}.png`), png);
    }
    for (const [scale, name] of SPLASH_SCALES) {
        await writeFile(join(ICON_DIR, name), await capture(pathToFileURL(join(ICON_DIR, 'splash.html')).href, SPLASH_SIZE.width, SPLASH_SIZE.height, scale));
    }
    await copyFile(join(ICON_DIR, 'app-icon-small.svg'), join(ROOT, 'src/main/resources/static/c/app-icon.svg'));
    console.log(`Wrote app.ico (${images.length} sizes) and ${SPLASH_SCALES.length} splash images to ${ICON_DIR}`);
} finally {
    await send('Browser.close').catch(() => {});
    socket.close();
    edge.kill();
    await new Promise((done) => setTimeout(done, 500));
    await rm(profile, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 }).catch(() => {});
}
