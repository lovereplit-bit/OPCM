import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { runInNewContext } from 'node:vm';
import ts from 'typescript';
import { describe, it, vi } from 'vitest';

// The setup wizard's connect step must survive its own effect re-running.
// switchConnection changes identity while the connect is in flight (the
// catalog load inside it updates state the hook chain depends on), so the
// original effect design — a `cancelled` flag flipped by the effect cleanup —
// dropped the finished connect's result and never reset `connecting`, leaving
// step 3 of the wizard on "the server is running" with grayed-out buttons
// forever. These ratchets pin the design that fixed it.
describe('onboarding setup connect step', () => {
  const sourcePromise = readFile(new URL('../../app/onboarding/setup.tsx', import.meta.url), 'utf8');

  it('never cancels the in-flight connect on an effect re-run', async () => {
    const source = await sourcePromise;

    // The one-shot guard is a ref, so a re-render cannot restart or cancel the run.
    assert.match(source, /connectAttemptedRef\.current/, 'the connect effect must be guarded by a ref');
    // And there is no cleanup function anymore that could mark the run cancelled.
    assert.doesNotMatch(source, /return \(\) => \{ cancelled = true/, 'the connect effect must not install a cancellation cleanup');

    // `connecting` must come back down no matter how the run ended, or the
    // buttons stay grayed out forever.
    assert.doesNotMatch(source, /if \(!cancelled\) setConnecting\(false\)/, 'setConnecting(false) must not be gated by a cancellation flag');
    assert.match(source, /finally \{\s*\n\s*setConnecting\(false\);/, 'setConnecting(false) must run in finally');
  });

  it('bounds the connect wait so a silent hang turns into a visible error', async () => {
    const source = await sourcePromise;

    assert.match(source, /CONNECT_TIMEOUT_MS/, 'the connect step must have a timeout constant');
    assert.match(source, /withConnectTimeout\(\s*\n?\s*switchConnection\(/, 'switchConnection must be wrapped in the timeout helper');
    // A failed connect must be retryable: the guard is released again.
    assert.match(source, /connectAttemptedRef\.current = false;/, 'a failed connect must release the one-shot guard');
  });

  it('rejects through withConnectTimeout when the connect never settles, and passes results through otherwise', async () => {
    const source = await sourcePromise;
    const match = source.match(/function withConnectTimeout[\s\S]*?\n\}/);
    assert.ok(match, 'withConnectTimeout must stay a module-level function in setup.tsx so this test can exercise the real one');

    const compiled = ts.transpileModule(`${match[0]}\nexport { withConnectTimeout };`, {
      compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
    }).outputText;

    vi.useFakeTimers();
    try {
      const exports: Record<string, unknown> = {};
      runInNewContext(compiled, { exports, setTimeout, clearTimeout });
      const withConnectTimeout = exports.withConnectTimeout as <T>(promise: Promise<T>, timeoutMs: number, message: string) => Promise<T>;

      // A pending connect rejects with the localized message once the deadline passes.
      // The error is created inside the vm context, so read its message without
      // relying on the outer realm's instanceof.
      const hung = withConnectTimeout(new Promise<never>(() => undefined), 30_000, 'too slow');
      const rejected = hung.then(
        () => 'resolved',
        (reason: { message?: string }) => `rejected: ${reason?.message ?? String(reason)}`,
      );
      await vi.advanceTimersByTimeAsync(30_001);
      assert.equal(await rejected, 'rejected: too slow');

      // A settling connect passes its value through untouched.
      assert.equal(await withConnectTimeout(Promise.resolve(42), 30_000, 'too slow'), 42);
      await assert.rejects(
        withConnectTimeout(Promise.reject(new Error('server said no')), 30_000, 'too slow'),
        /server said no/,
      );

      // A settled connect leaves no timer behind.
      const timers = vi.getTimerCount();
      assert.equal(timers, 0, `expected no pending timers, found ${timers}`);
    } finally {
      vi.useRealTimers();
    }
  });
});
