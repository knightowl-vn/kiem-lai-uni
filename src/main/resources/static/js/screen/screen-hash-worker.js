/**
 * KiemLai Universe — Screen Video Incremental SHA-256 Web Worker (MS-08E3-S2)
 *
 * Runs off the main browser thread to calculate standard FIPS PUB 180-4 SHA-256
 * in bounded memory slices without UI thread freezing or tab crash.
 */
'use strict';

const K_SHA256 = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
    0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
    0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
    0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
];

function IncrementalSha256() {
    this.h = new Int32Array([
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19
    ]);
    this.buffer = new Uint8Array(64);
    this.bufferLength = 0;
    this.totalBytes = 0;
    this.w = new Int32Array(64);
    this.finalized = false;
}

IncrementalSha256.prototype._processBlock = function (block, offset) {
    const w = this.w;
    for (let i = 0; i < 16; i++) {
        const idx = offset + (i << 2);
        w[i] = ((block[idx] << 24) | (block[idx + 1] << 16) | (block[idx + 2] << 8) | block[idx + 3]) | 0;
    }
    for (let i = 16; i < 64; i++) {
        const w15 = w[i - 15];
        const s0 = ((w15 >>> 7) | (w15 << 25)) ^ ((w15 >>> 18) | (w15 << 14)) ^ (w15 >>> 3);
        const w2 = w[i - 2];
        const s1 = ((w2 >>> 17) | (w2 << 15)) ^ ((w2 >>> 19) | (w2 << 13)) ^ (w2 >>> 10);
        w[i] = (w[i - 16] + s0 + w[i - 7] + s1) | 0;
    }

    let a = this.h[0], b = this.h[1], c = this.h[2], d = this.h[3];
    let e = this.h[4], f = this.h[5], g = this.h[6], h = this.h[7];

    for (let i = 0; i < 64; i++) {
        const S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        const ch = (e & f) ^ ((~e) & g);
        const temp1 = (h + S1 + ch + K_SHA256[i] + w[i]) | 0;
        const S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        const maj = (a & b) ^ (a & c) ^ (b & c);
        const temp2 = (S0 + maj) | 0;

        h = g;
        g = f;
        f = e;
        e = (d + temp1) | 0;
        d = c;
        c = b;
        b = a;
        a = (temp1 + temp2) | 0;
    }

    this.h[0] = (this.h[0] + a) | 0;
    this.h[1] = (this.h[1] + b) | 0;
    this.h[2] = (this.h[2] + c) | 0;
    this.h[3] = (this.h[3] + d) | 0;
    this.h[4] = (this.h[4] + e) | 0;
    this.h[5] = (this.h[5] + f) | 0;
    this.h[6] = (this.h[6] + g) | 0;
    this.h[7] = (this.h[7] + h) | 0;
};

IncrementalSha256.prototype.update = function (chunk) {
    if (this.finalized) throw new Error('Cannot update finalized hash');
    const uint8 = (chunk instanceof Uint8Array) ? chunk : new Uint8Array(chunk);
    let offset = 0;
    let len = uint8.length;
    this.totalBytes += len;

    if (this.bufferLength > 0) {
        const need = 64 - this.bufferLength;
        if (len >= need) {
            this.buffer.set(uint8.subarray(0, need), this.bufferLength);
            this._processBlock(this.buffer, 0);
            this.bufferLength = 0;
            offset = need;
            len -= need;
        } else {
            this.buffer.set(uint8, this.bufferLength);
            this.bufferLength += len;
            return this;
        }
    }

    while (len >= 64) {
        this._processBlock(uint8, offset);
        offset += 64;
        len -= 64;
    }

    if (len > 0) {
        this.buffer.set(uint8.subarray(offset, offset + len), 0);
        this.bufferLength = len;
    }
    return this;
};

IncrementalSha256.prototype.digest = function () {
    if (this.finalized) throw new Error('Digest already called');
    this.finalized = true;

    const totalBits = this.totalBytes * 8;
    const highBits = Math.floor(totalBits / 0x100000000);
    const lowBits = (totalBits >>> 0);

    this.buffer[this.bufferLength++] = 0x80;

    if (this.bufferLength > 56) {
        this.buffer.fill(0, this.bufferLength, 64);
        this._processBlock(this.buffer, 0);
        this.bufferLength = 0;
    }

    this.buffer.fill(0, this.bufferLength, 56);

    this.buffer[56] = (highBits >>> 24) & 0xff;
    this.buffer[57] = (highBits >>> 16) & 0xff;
    this.buffer[58] = (highBits >>> 8) & 0xff;
    this.buffer[59] = highBits & 0xff;

    this.buffer[60] = (lowBits >>> 24) & 0xff;
    this.buffer[61] = (lowBits >>> 16) & 0xff;
    this.buffer[62] = (lowBits >>> 8) & 0xff;
    this.buffer[63] = lowBits & 0xff;

    this._processBlock(this.buffer, 0);

    let hex = '';
    for (let i = 0; i < 8; i++) {
        hex += (this.h[i] >>> 0).toString(16).padStart(8, '0');
    }
    return hex;
};

self.onmessage = async function (e) {
    const data = e.data || {};
    if (data.type !== 'HASH') return;

    const file = data.file;
    const chunkSize = data.chunkSize || (2 * 1024 * 1024);

    if (!file || typeof file.slice !== 'function') {
        self.postMessage({ type: 'ERROR', message: 'No file provided or file does not support slice()' });
        return;
    }

    try {
        const hasher = new IncrementalSha256();
        const totalSize = typeof file.size === 'number' ? file.size : 0;
        let offset = 0;

        while (offset < totalSize) {
            const end = Math.min(offset + chunkSize, totalSize);
            const slice = file.slice(offset, end);
            const arrayBuffer = await slice.arrayBuffer();
            hasher.update(new Uint8Array(arrayBuffer));
            offset = end;

            if (totalSize > 0) {
                const percent = Math.floor((offset / totalSize) * 100);
                self.postMessage({
                    type: 'PROGRESS',
                    percent: percent,
                    hashedBytes: offset,
                    totalBytes: totalSize
                });
            }
        }

        const digest = hasher.digest();
        self.postMessage({ type: 'COMPLETE', digest: digest });
    } catch (err) {
        self.postMessage({ type: 'ERROR', message: err ? err.message : 'Unknown hashing error' });
    }
};

if (typeof module !== 'undefined' && module.exports) {
    module.exports = { IncrementalSha256, K_SHA256 };
}
