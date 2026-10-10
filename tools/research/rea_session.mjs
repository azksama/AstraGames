import { spawn } from 'node:child_process';
import { createInterface } from 'node:readline';
import { mkdir, readFile, readdir, writeFile, access } from 'node:fs/promises';
import { resolve, join } from 'node:path';

// Local research client for the installed REA 6.3.0 MCP stdio server.
// No agent registration, global configuration, target bytes or provider scripts
// are changed. The documented large-binary startup deadline applies only to
// this owned process. Requests remain ordinary public REA tool calls.
const [reaRoot, ghidraRoot, javaHome, outputArg, target, snapshot] = process.argv.slice(2);
if (![reaRoot, ghidraRoot, javaHome, outputArg, target].every(Boolean)) {
  throw new Error('rea-session: need REA root, Ghidra root, Java home, output, target, optional snapshot');
}
const output = resolve(outputArg);
const requests = join(output, 'requests');
await mkdir(requests, { recursive: true });
await mkdir(join(output, 'responses'), { recursive: true });
const child = spawn(process.execPath, [join(reaRoot, 'scripts', 'rea.mjs'), '--mcp'], {
  env: { ...process.env, REA_ANALYSIS_PROVIDER: 'ghidra', GHIDRA_INSTALL_DIR: ghidraRoot,
    JAVA_HOME: javaHome, REA_GHIDRA_STARTUP_TIMEOUT_MS: '1200000' },
  windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'],
});
let sequence = 0;
const pending = new Map();
const parsed = (r) => r?.structuredContent ?? (() => {
  const c = r?.content?.find(c => c.type === 'text');
  try { return JSON.parse(c?.text ?? 'null'); } catch { return { text: c?.text }; }
})();
const progress = (value) => process.stdout.write(JSON.stringify({ date: new Date().toISOString(), ...value }) + '\n');
createInterface({ input: child.stdout }).on('line', line => {
  let packet;
  try { packet = JSON.parse(line); } catch { return; }
  if (packet.id !== undefined) {
    const p = pending.get(packet.id);
    if (!p) return;
    pending.delete(packet.id);
    clearTimeout(p.timer);
    if (packet.error) p.reject(new Error(JSON.stringify(packet.error)));
    else p.resolve(packet.result);
  }
});
child.on('exit', (code, signal) => {
  for (const p of pending.values()) { clearTimeout(p.timer); p.reject(new Error(`REA exited ${code}/${signal}`)); }
  pending.clear();
});
child.on('error', error => {
  for (const p of pending.values()) { clearTimeout(p.timer); p.reject(error); }
  pending.clear();
});
let stderr = '';
child.stderr.on('data', data => { stderr = (stderr + data.toString()).slice(-100000); });
async function rpc(method, params) {
  const id = ++sequence;
  return await new Promise((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`Client deadline: ${method}`)); }, 1300000);
    pending.set(id, { resolve, reject, timer });
    child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  });
}
async function call(id, name, args) {
  progress({ id, name, state: 'running' });
  let result;
  try { result = await rpc('tools/call', { name, arguments: args }); }
  catch (error) { result = { isError: true, error: String(error) }; }
  await writeFile(join(output, 'responses', id + '.json'), JSON.stringify(result, null, 2), { flag: 'wx' });
  const j = parsed(result);
  progress({ id, name, state: result.isError || j?.error ? 'error' : 'complete',
    evidence_id: j?.evidence_id, keys: j ? Object.keys(j) : [],
    error: j?.error ?? result.error, items: Array.isArray(j?.normalized_result) ? j.normalized_result.length : undefined });
  return result;
}
const allowed = new Set(['binary_session', 'binary_overview', 'search_strings', 'search_procedures',
  'list_names', 'xrefs', 'resolve_containing_procedure', 'inspect_address_context',
  'analyze_function', 'procedure_pseudo_code', 'read_function_instructions',
  'inspect_native_instruction', 'read_bytes', 'address_to_file_offset',
  'record_unknown', 'export_evidence_bundle', 'close_binary']);
const done = new Set();
let opened = false;
try {
  const init = await rpc('initialize', { protocolVersion: '2025-06-18', capabilities: {},
    clientInfo: { name: 'astra-wolf-research', version: '1' } });
  await writeFile(join(output, 'initialize.json'), JSON.stringify(init, null, 2), { flag: 'wx' });
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
  const r = await call('00-open', 'open_binary', { path: resolve(target), provider_id: 'ghidra',
    ...(snapshot ? { snapshot_path: resolve(snapshot) } : {}) });
  if (r.isError || parsed(r)?.error) throw new Error('Could not open research target');
  opened = true;
  await call('01-overview', 'binary_overview', {});
  progress({ state: 'accepting-requests', output });
  while (true) {
    try { await access(join(output, 'stop')); break; } catch { /* no stop request */ }
    for (const file of (await readdir(requests)).sort()) {
      if (done.has(file) || !/^[0-9A-Za-z_-]+\.json$/.test(file)) continue;
      done.add(file);
      const request = JSON.parse((await readFile(join(requests, file), 'utf8')).replace(/^\uFEFF/, ''));
      if (!allowed.has(request.name)) throw new Error(`Unsupported research tool: ${request.name}`);
      const response = await call(file.slice(0, -5), request.name, request.arguments ?? {});
      if (request.name === 'close_binary' && !response.isError && !parsed(response)?.error) opened = false;
    }
    await new Promise(resolve => setTimeout(resolve, 250));
  }
} finally {
  if (opened) await call('99-close', 'close_binary', {});
  await writeFile(join(output, 'rea-stderr.txt'), stderr);
  child.stdin.end();
  if (child.exitCode === null) {
    await Promise.race([new Promise(resolve => child.once('exit', resolve)), new Promise(resolve => setTimeout(resolve, 10000))]);
    if (child.exitCode === null) { progress({ state: 'cleanup-unconfirmed' }); child.kill(); }
  }
  progress({ state: 'closed', exitCode: child.exitCode });
}
