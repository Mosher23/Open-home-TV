import { test } from "node:test";
import assert from "node:assert/strict";
import { createCipheriv, createDecipheriv } from "node:crypto";
import { requestEnvelope, readReply, base64 } from "./protocol.js";
const key = Uint8Array.from({ length: 32 }, (_, i) => i),
    iv = Uint8Array.from({ length: 12 }, (_, i) => i + 1);
test("browser requests interoperate with standard AES-256-GCM and hide credentials", () => {
    const message = { op: "connect", token: "private-fixture-token" };
    const packet = requestEnvelope(key, message, iv),
        encrypted = Buffer.from(packet.data, "base64");
    assert.ok(!JSON.stringify(packet).includes(message.token));
    const cipher = createDecipheriv("aes-256-gcm", key, iv);
    cipher.setAAD(Buffer.from("OpenHomeTV setup v1 request"));
    cipher.setAuthTag(encrypted.subarray(-16));
    assert.deepEqual(
        JSON.parse(
            Buffer.concat([
                cipher.update(encrypted.subarray(0, -16)),
                cipher.final(),
            ]),
        ),
        message,
    );
});
test("browser accepts standard replies and rejects a swapped nonce or corrupt message", () => {
    const requestNonce = base64(iv),
        replyIv = Uint8Array.from(iv, (v) => v + 20);
    const cipher = createCipheriv("aes-256-gcm", key, replyIv);
    cipher.setAAD(Buffer.from(`OpenHomeTV setup v1 response:${requestNonce}`));
    const data = Buffer.concat([
        cipher.update(JSON.stringify({ ok: true })),
        cipher.final(),
        cipher.getAuthTag(),
    ]);
    const packet = { iv: base64(replyIv), data: base64(data) };
    assert.deepEqual(readReply(key, packet, requestNonce), { ok: true });
    assert.throws(() => readReply(key, packet, "different-request"));
    data[0] ^= 1;
    assert.throws(() =>
        readReply(key, { ...packet, data: base64(data) }, requestNonce),
    );
});
test("large encrypted envelopes do not depend on JavaScript spread argument limits", () => {
    const bytes = new Uint8Array(180000);
    assert.equal(Buffer.from(base64(bytes), "base64").length, bytes.length);
});
