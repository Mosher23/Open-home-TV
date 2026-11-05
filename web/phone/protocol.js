import { gcm } from "@noble/ciphers/aes";
const encoder = new TextEncoder(),
    decoder = new TextDecoder();
export function base64(bytes) {
    let binary = "";
    for (let offset = 0; offset < bytes.length; offset += 32768)
        binary += String.fromCharCode(
            ...bytes.subarray(offset, offset + 32768),
        );
    return btoa(binary);
}
export function fromBase64(value) {
    return Uint8Array.from(
        atob(value.replace(/-/g, "+").replace(/_/g, "/")),
        (c) => c.charCodeAt(0),
    );
}
export function requestEnvelope(key, message, iv) {
    if (key.length !== 32 || iv.length !== 12)
        throw Error("Invalid pairing key or nonce");
    return {
        iv: base64(iv),
        data: base64(
            gcm(key, iv, encoder.encode("OpenHomeTV setup v1 request")).encrypt(
                encoder.encode(JSON.stringify(message)),
            ),
        ),
    };
}
export function readReply(key, envelope, requestNonce) {
    return JSON.parse(
        decoder.decode(
            gcm(
                key,
                fromBase64(envelope.iv),
                encoder.encode(`OpenHomeTV setup v1 response:${requestNonce}`),
            ).decrypt(fromBase64(envelope.data)),
        ),
    );
}
