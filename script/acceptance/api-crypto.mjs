#!/usr/bin/env node
/**
 * DataGate M1 验收辅助：按 ruoyi-common-encrypt 的 CryptoFilter 协议加密请求体。
 *
 * 协议（见 DecryptRequestBodyWrapper / EncryptUtils）：
 *   1. 随机 16 字符 AES 口令 aesPwd
 *   2. header `encrypt-key` = RSA/ECB/PKCS1Padding 公钥加密( Base64(aesPwd) ) → Base64
 *   3. body = AES/ECB/PKCS5Padding 加密( 明文JSON, aesPwd 的 UTF-8 字节 ) → Base64
 *
 * 用法：node api-crypto.mjs encrypt <publicKeyB64> <jsonBody>
 * 输出：JSON 字符串 { "header": "...", "body": "..." }
 *
 * 另提供 TOTP（RFC 6238，SHA-1，30s 步长）用于 M1-01 绑定确认：
 *   node api-crypto.mjs totp <base32Secret>
 * 输出：6 位数字验证码
 */
import crypto from 'node:crypto';

const [, , mode, arg1, arg2] = process.argv;

if (mode === 'encrypt') {
  const publicKeyB64 = arg1;
  const plainJson = arg2;
  const publicKey = crypto.createPublicKey({
    key: Buffer.from(publicKeyB64, 'base64'),
    format: 'der',
    type: 'spki',
  });
  // 16 位随机 AES 口令（ hutool SecureUtil.aes 接受 16/24/32 字节 ）
  const aesPwd = crypto.randomBytes(16).toString('hex').slice(0, 16);
  // RSA 加密 base64(aesPwd)
  const inner = Buffer.from(aesPwd, 'utf8').toString('base64');
  const header = crypto.publicEncrypt(
    { key: publicKey, padding: crypto.constants.RSA_PKCS1_PADDING },
    Buffer.from(inner, 'utf8'),
  ).toString('base64');
  // AES/ECB/PKCS5 加密请求体
  const cipher = crypto.createCipheriv('aes-128-ecb', Buffer.from(aesPwd, 'utf8'), null);
  const body = Buffer.concat([cipher.update(plainJson, 'utf8'), cipher.final()]).toString('base64');
  process.stdout.write(JSON.stringify({ header, body }));
} else if (mode === 'totp') {
  // Base32 解码
  const alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  const clean = arg1.replace(/=+$/, '').toUpperCase();
  let bits = 0, value = 0;
  const bytes = [];
  for (const ch of clean) {
    value = (value << 5) | alphabet.indexOf(ch);
    bits += 5;
    if (bits >= 8) { bytes.push((value >>> (bits - 8)) & 0xff); bits -= 8; }
  }
  const key = Buffer.from(bytes);
  const counter = Math.floor(Date.now() / 1000 / 30);
  const buf = Buffer.alloc(8);
  buf.writeBigUInt64BE(BigInt(counter));
  const hmac = crypto.createHmac('sha1', key).update(buf).digest();
  const offset = hmac[hmac.length - 1] & 0x0f;
  const code = ((hmac[offset] & 0x7f) << 24 | hmac[offset + 1] << 16 | hmac[offset + 2] << 8 | hmac[offset + 3]) % 1000000;
  process.stdout.write(String(code).padStart(6, '0'));
} else {
  console.error('usage: node api-crypto.mjs encrypt <publicKeyB64> <jsonBody> | totp <base32Secret>');
  process.exit(2);
}
