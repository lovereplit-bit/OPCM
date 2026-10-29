import { createRequire } from 'node:module';
import { describe, expect, it } from 'vitest';

const require = createRequire(import.meta.url);
const { applyGradleProperties, withAndroidAppConfig } = require('../../config/plugins/android.cjs') as {
  applyGradleProperties: (items: any[]) => any[];
  withAndroidAppConfig: (config: any, options: { isDevelopmentVariant: boolean; isFossVariant: boolean }) => any;
};
const { withoutPushEntitlement } = require('../../config/plugins/ios.cjs') as {
  withoutPushEntitlement: (config: any) => any;
};

function androidConfig() {
  return {
    name: 'Test',
    slug: 'test',
    mods: { android: {} },
    modRequest: { projectRoot: '/tmp/project' },
  };
}

describe('Expo config plugins', () => {
  it('replaces Gradle memory, R8 and native library packaging properties without touching unrelated items', () => {
    const items = [
      { type: 'property', key: 'org.gradle.jvmargs', value: 'old' },
      { type: 'property', key: 'android.r8.optimizedResourceShrinking', value: 'false' },
      { type: 'property', key: 'expo.useLegacyPackaging', value: 'false' },
      { type: 'property', key: 'keep.me', value: 'yes' },
      { type: 'comment', value: 'keep' },
    ];
    expect(applyGradleProperties(items)).toEqual([
      { type: 'property', key: 'keep.me', value: 'yes' },
      { type: 'comment', value: 'keep' },
      { type: 'property', key: 'org.gradle.jvmargs', value: '-Xmx4096m -XX:MaxMetaspaceSize=1024m -Dfile.encoding=UTF-8' },
      { type: 'property', key: 'android.r8.optimizedResourceShrinking', value: 'true' },
      // Forced on: the local server exec()s libproot.so from nativeLibraryDir,
      // which only exists when the installer extracts the native libraries.
      { type: 'property', key: 'expo.useLegacyPackaging', value: 'true' },
    ]);
  });

  it('overrides the ML Kit scanner orientation only in non-FOSS builds', async () => {
    const production = withAndroidAppConfig(androidConfig(), { isDevelopmentVariant: true, isFossVariant: false });
    const manifest = { manifest: { application: [{ $: {} }] } };
    const result = await production.mods.android.manifest({ ...production, modResults: manifest });
    const application = result.modResults.manifest.application[0];
    expect(application.$['android:usesCleartextTraffic']).toBe('true');
    // The local server exec()s libproot.so from nativeLibraryDir, which only
    // exists when the installer extracts the native libraries.
    expect(application.$['android:extractNativeLibs']).toBe('true');
    expect(application.activity).toContainEqual({
      $: {
        'android:name': 'com.google.mlkit.vision.codescanner.internal.GmsBarcodeScanningDelegateActivity',
        'android:screenOrientation': 'unspecified',
        'tools:replace': 'android:screenOrientation',
      },
    });

    const foss = withAndroidAppConfig(androidConfig(), { isDevelopmentVariant: true, isFossVariant: true });
    const fossResult = await foss.mods.android.manifest({ ...foss, modResults: { manifest: { application: [{ $: {} }] } } });
    expect(fossResult.modResults.manifest.application[0].activity).toBeUndefined();
    expect(fossResult.modResults.manifest.application[0].$['android:extractNativeLibs']).toBe('true');
  });

  it('enables native library extraction in the manifest for every variant', async () => {
    for (const options of [
      { isDevelopmentVariant: true, isFossVariant: false },
      { isDevelopmentVariant: false, isFossVariant: false },
    ]) {
      const config = withAndroidAppConfig(androidConfig(), options);
      const result = await config.mods.android.manifest({
        ...config,
        modResults: { manifest: { application: [{ $: {} }] } },
      });
      expect(result.modResults.manifest.application[0].$['android:extractNativeLibs']).toBe('true');
    }
  });

  it('removes the push entitlement while preserving other entitlements', async () => {
    const config = { mods: { ios: {} } };
    const plugin = withoutPushEntitlement(config);
    const result = await plugin.mods.ios.entitlements({
      ...plugin,
      modResults: { 'aps-environment': 'development', 'com.apple.developer.associated-domains': ['applinks:example.test'] },
    });
    expect(result.modResults).toEqual({ 'com.apple.developer.associated-domains': ['applinks:example.test'] });
  });
});
