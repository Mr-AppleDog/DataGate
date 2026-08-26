#!/usr/bin/env node
/**
 * 独立重算 dbg_audit_event 哈希链（与 AuditHashChain.java 公式完全一致）：
 *   eventHash = SHA-256( prevHash|eventId|category|action|actorId|canon(actorSnapshot)|
 *                        targetType|targetId|canon(targetSnapshot)|result|sourceIp|traceId|
 *                        canon(details)|occurredAt )
 *   canon(map)  = "{k=v;k=v;}"（键排序、嵌套递归；空 map → ""）
 *   canon(list) = "[v,v,]"（每个元素后跟逗号；空 list → "[]"，与 Java 实现一致）
 *   canon(null) = ""；数字保留 PG jsonb 输出字面值（对齐 Jackson→String.valueOf）
 * 用法: node audit-verify.mjs <audit-export.json>
 * 输出: JSON { total, intact, brokenAtId, failures[] }
 */
import crypto from 'node:crypto';
import fs from 'node:fs';

const file = process.argv[2];
const rows = JSON.parse(fs.readFileSync(file, 'utf8').replace(/^﻿/, ''));

function canon(node) {
  if (node === null || node === undefined) return '';
  if (typeof node === 'object' && node.__raw !== undefined) return node.__raw;
  if (Array.isArray(node)) return '[' + node.map(v => canon(v) + ',').join('') + ']';
  if (typeof node === 'object') {
    const keys = Object.keys(node).sort();
    if (!keys.length) return '';
    return '{' + keys.map(k => k + '=' + canon(node[k]) + ';').join('') + '}';
  }
  return String(node);
}

// 保留数字字面值的 JSON 子集解析器（覆盖 jsonb 输出：对象/数组/字符串/数字/true/false/null）
function parseKeepNumbers(text) {
  let i = 0;
  const skipWs = () => { while (i < text.length && /\s/.test(text[i])) i++; };
  function parseString() {
    let s = ''; i++;
    while (text[i] !== '"') {
      if (text[i] === '\\') {
        const e = text[++i];
        if (e === 'u') { s += String.fromCharCode(parseInt(text.slice(i + 1, i + 5), 16)); i += 5; }
        else { s += ({ n: '\n', t: '\t', r: '\r', b: '\b', f: '\f' })[e] ?? e; i++; }
      } else s += text[i++];
    }
    i++; return s;
  }
  function parseValue() {
    skipWs();
    const ch = text[i];
    if (ch === '{') {
      i++; const obj = {}; skipWs();
      if (text[i] === '}') { i++; return obj; }
      for (;;) { skipWs(); const k = parseString(); skipWs(); i++; obj[k] = parseValue(); skipWs();
        if (text[i] === ',') { i++; continue; } i++; return obj; }
    }
    if (ch === '[') {
      i++; const arr = []; skipWs();
      if (text[i] === ']') { i++; return arr; }
      for (;;) { arr.push(parseValue()); skipWs();
        if (text[i] === ',') { i++; continue; } i++; return arr; }
    }
    if (ch === '"') return parseString();
    if (text.startsWith('true', i)) { i += 4; return true; }
    if (text.startsWith('false', i)) { i += 5; return false; }
    if (text.startsWith('null', i)) { i += 4; return null; }
    const m = /^-?\d+(\.\d+)?([eE][+-]?\d+)?/.exec(text.slice(i));
    if (!m) throw new Error('jsonb parse error at ' + i + ': ' + text.slice(i, i + 30));
    i += m[0].length; return { __raw: m[0] };
  }
  return parseValue();
}

const sha256hex = s => crypto.createHash('sha256').update(s, 'utf8').digest('hex');
const nul = v => (v === null || v === undefined ? '' : String(v));

let expectedPrev = 'GENESIS';
let intact = true;
let brokenAtId = null;
const failures = [];

for (const r of rows) {
  const canonical = [
    nul(r.previous_hash), nul(r.event_id), nul(r.category), nul(r.action),
    r.actor_id === null ? '' : String(r.actor_id),
    canon(r.actor_snapshot === null ? null : parseKeepNumbers(r.actor_snapshot)),
    nul(r.target_type), nul(r.target_id),
    canon(r.target_snapshot === null ? null : parseKeepNumbers(r.target_snapshot)),
    nul(r.result), nul(r.source_ip), nul(r.trace_id),
    canon(r.details === null ? null : parseKeepNumbers(r.details)),
    nul(r.occurred_iso),
  ].join('|');
  const recomputed = sha256hex(canonical);
  const linkOk = r.previous_hash === expectedPrev;
  const hashOk = recomputed === r.event_hash;
  if (!linkOk || !hashOk) {
    intact = false;
    if (brokenAtId === null) brokenAtId = r.id;
    failures.push({ id: r.id, event_id: r.event_id, linkOk, hashOk });
  }
  expectedPrev = r.event_hash;
}

console.log(JSON.stringify({ total: rows.length, intact, brokenAtId, failures: failures.slice(0, 5) }, null, 2));
