import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

// Execute the production page's watchdog, including the browser's predecode-drop case.
const html = readFileSync(new URL('../app/src/main/assets/camera/rtc.html', import.meta.url), 'utf8');
const source = html.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replaceAll('__RUN__', '1').replaceAll('__TRICKLE__', 'true')
  .replaceAll('__CONFIG__', JSON.stringify('{}'));

async function fixture() {
  let now = 0;
  let quality = { totalVideoFrames: 0, droppedVideoFrames: 0 };
  const callbacks = new Set();
  const playing = [];
  const failures = [];
  const video = {
    readyState: 4, videoWidth: 640, videoHeight: 360,
    getVideoPlaybackQuality: () => quality,
  };
  class Peer {
    addTransceiver() {}
    async createOffer() { return { type: 'offer', sdp: 'v=0' }; }
    async setLocalDescription(offer) { this.localDescription = offer; }
    close() {}
  }
  const context = vm.createContext({
    document: { getElementById: () => video },
    window: {},
    Date: { now: () => now },
    RTCPeerConnection: Peer,
    NativeRtc: {
      playing: run => playing.push(run), failed: (run, code) => failures.push(code),
      offer() {}, candidate() {},
    },
    setInterval(callback) { callbacks.add(callback); return callback; },
    clearInterval(callback) { callbacks.delete(callback); },
  });
  vm.runInContext(source, context);
  await new Promise(resolve => setImmediate(resolve));
  return {
    playing, failures, video,
    tick(time, total, dropped) {
      now = time;
      quality = { totalVideoFrames: total, droppedVideoFrames: dropped };
      for (const callback of [...callbacks]) callback();
    },
  };
}

test('metadata and only-dropped frames never report live, then request fallback', async () => {
  const f = await fixture();
  f.tick(0, 0, 0);
  f.tick(1000, 30, 30);
  f.tick(26000, 780, 780);
  assert.equal(f.playing.length, 0);
  assert.deepEqual(f.failures, ['NO_VIDEO']);
});

test('real progress sends heartbeats; later dropped frames do not hide a stall', async () => {
  const f = await fixture();
  f.tick(1000, 10, 9);
  f.tick(2000, 11, 9);
  assert.equal(f.playing.length, 2);
  f.tick(3000, 12, 10);
  assert.equal(f.playing.length, 2);
  f.tick(28000, 100, 98);
  assert.deepEqual(f.failures, ['VIDEO_STALLED']);
});

test('frame counters without video dimensions are not playback', async () => {
  const f = await fixture();
  f.video.videoHeight = 0;
  f.tick(1000, 10, 0);
  f.tick(26000, 100, 0);
  assert.equal(f.playing.length, 0);
  assert.deepEqual(f.failures, ['NO_VIDEO']);
});
