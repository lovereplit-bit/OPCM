import Constants from 'expo-constants';
import { requireOptionalNativeModule } from 'expo';
import { Platform } from 'react-native';

import { createProfileId, mutateConnectionProfiles, saveProfilePassword, type ConnectionProfile } from '@/lib/connection-profiles';
import { getConnectionScope } from '@/lib/connection-scope';

// The self-hosted OpenCode server. Everything the user would otherwise enter
// by hand — the loopback address, the password, the nickname — is generated
// by the native module once and reused, so the first launch goes straight
// from "welcome" to a working chat.

export type LocalServerPhase = 'idle' | 'downloading' | 'installing' | 'starting' | 'running' | 'failed';

export type LocalServerInfo = {
  phase: LocalServerPhase;
  message: string;
  progress: number;
  port: number;
  password: string;
  nick: string;
  version: string;
  installed: boolean;
  running: boolean;
  compat: boolean;
  apiKey?: string | null;
};

export type LocalServerEvent = {
  phase: LocalServerPhase;
  message: string;
  progress: number;
  port: number;
  version: string;
};

type NativeOpencodeServer = {
  getServerInfo(): LocalServerInfo;
  isRuntimeInstalled(): boolean;
  start(manifestUrl: string): Promise<LocalServerInfo>;
  restart(manifestUrl?: string): Promise<LocalServerInfo>;
  stop(): Promise<LocalServerInfo>;
  setApiKey(apiKey?: string | null): Promise<LocalServerInfo>;
  setCompatMode(enabled: boolean): Promise<LocalServerInfo>;
  getLogs(): Promise<string>;
  fetchRuntimeManifest(manifestUrl: string): Promise<{ version: string; sha256: string; size: number; url: string }>;
  addListener(event: 'onServerEvent', listener: (event: LocalServerEvent) => void): { remove: () => void };
};

export function isLocalServerSupported(platform: string = Platform.OS) {
  return platform === 'android';
}

const nativeModule: NativeOpencodeServer | null = isLocalServerSupported()
  ? requireOptionalNativeModule<NativeOpencodeServer>('OpencodeServer')
  : null;

function getNativeModule(): NativeOpencodeServer | null {
  return nativeModule;
}

export function getRuntimeManifestUrl() {
  const extra = Constants.expoConfig?.extra as { runtime?: { manifestUrl?: string } } | undefined;
  return extra?.runtime?.manifestUrl ?? 'https://github.com/lovereplit-bit/OPCM/releases/latest/download/opencode-runtime.json';
}

/** The current server state; on unsupported platforms it reports an idle, uninstalled server. */
export function getLocalServerInfo(): LocalServerInfo {
  const native = getNativeModule();
  if (!native) {
    return {
      phase: 'idle', message: '', progress: -1, port: 0, password: '', nick: '', version: '',
      installed: false, running: false, compat: false,
    };
  }
  return native.getServerInfo();
}

export function isLocalRuntimeInstalled() {
  return getNativeModule()?.isRuntimeInstalled() ?? false;
}

export async function startLocalServer() {
  const native = getNativeModule();
  if (!native) throw new Error('The local server is only available on Android.');
  return native.start(getRuntimeManifestUrl());
}

export async function restartLocalServer() {
  const native = getNativeModule();
  if (!native) throw new Error('The local server is only available on Android.');
  return native.restart(getRuntimeManifestUrl());
}

export async function stopLocalServer() {
  const native = getNativeModule();
  if (!native) throw new Error('The local server is only available on Android.');
  return native.stop();
}

export async function setLocalServerApiKey(apiKey: string | null) {
  const native = getNativeModule();
  if (!native) throw new Error('The local server is only available on Android.');
  return native.setApiKey(apiKey);
}

export async function setLocalServerCompatMode(enabled: boolean) {
  const native = getNativeModule();
  if (!native) throw new Error('The local server is only available on Android.');
  return native.setCompatMode(enabled);
}

export async function getLocalServerLogs() {
  return getNativeModule()?.getLogs() ?? '';
}

export async function fetchRuntimeManifest() {
  const native = getNativeModule();
  if (!native) throw new Error('The local server is only available on Android.');
  return native.fetchRuntimeManifest(getRuntimeManifestUrl());
}

/** Subscribes to native server state publishes. Returns an unsubscribe function. */
export function subscribeLocalServer(listener: (event: LocalServerEvent) => void) {
  const native = getNativeModule();
  if (!native) return () => undefined;
  const subscription = native.addListener('onServerEvent', listener);
  return () => subscription.remove();
}

export const LOCAL_PROFILE_NAME = 'OpenCode on this phone';

export function localServerUrl(port: number) {
  return `http://127.0.0.1:${port}`;
}

/**
 * True when the profile is the auto-created local one: it rides the loopback
 * address of this phone's own server.
 */
export function isLocalProfile(profile: Pick<ConnectionProfile, 'serverUrl'>) {
  return /^http:\/\/127\.0\.0\.1:\d+\/?$/.test(profile.serverUrl.trim());
}

/**
 * Creates or refreshes the local connection profile from the generated
 * identity (password, nickname, port) and returns the credentials the caller
 * should hand to `switchConnection`. The password lands in SecureStore, the
 * metadata in AsyncStorage, exactly like a manually created profile.
 */
export async function ensureLocalServerProfile(info: LocalServerInfo): Promise<{ profile: ConnectionProfile; serverUrl: string; username: string; password: string }> {
  const serverUrl = localServerUrl(info.port);

  const profile = await mutateConnectionProfiles(async (profiles, save) => {
    const existing = profiles.find((entry) => isLocalProfile(entry));
    const model = existing ?? {
      id: createProfileId(),
      name: info.nick || LOCAL_PROFILE_NAME,
      serverUrl,
      username: info.nick,
    } satisfies ConnectionProfile;

    const updated: ConnectionProfile = {
      ...model,
      // The nickname is the human-visible identity of this install.
      name: existing?.name && existing.name !== LOCAL_PROFILE_NAME ? existing.name : (info.nick || LOCAL_PROFILE_NAME),
      serverUrl,
      username: info.nick,
    };

    const next = existing
      ? profiles.map((entry) => (entry.id === existing.id ? updated : entry))
      : [...profiles, updated];

    await save(next);

    return updated;
  });

  await saveProfilePassword(profile.id, info.password);

  return { profile, serverUrl, username: info.nick, password: info.password };
}

/** The connection scope of the local profile, used to find it later. */
export function localProfileScope(profile: Pick<ConnectionProfile, 'serverUrl' | 'username'>) {
  return getConnectionScope({ serverUrl: profile.serverUrl, username: profile.username });
}
