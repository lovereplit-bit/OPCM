import { AppState, Platform } from 'react-native';

// The OCM sound set: short WAV cues that accompany key agent events.
//   attention.wav  — the agent needs input (permission, question, form)
//   quota.wav      — a rate limit / quota / "too many requests" rejection
//   task_done.wav  — a tracked task finished
//
// Sounds play in-app through expo-audio whenever the event is observed with
// the app on screen; background delivery keeps using notification channels.

export type OcmSoundName = 'attention' | 'quota' | 'task_done';

const SOURCES: Record<OcmSoundName, number> = {
  attention: require('../assets/sounds/attention.wav') as number,
  quota: require('../assets/sounds/quota.wav') as number,
  task_done: require('../assets/sounds/task_done.wav') as number,
};

type LoadedPlayer = { play: () => void };

let audioModulePromise: Promise<typeof import('expo-audio') | null> | null = null;
const players = new Map<OcmSoundName, LoadedPlayer>();
let enabled = true;

async function getAudioModuleAsync() {
  if (!audioModulePromise) {
    audioModulePromise = import('expo-audio')
      .then((module) => module)
      .catch(() => null);
  }
  return audioModulePromise;
}

function getPlayer(name: OcmSoundName, module: typeof import('expo-audio')): LoadedPlayer | null {
  const existing = players.get(name);
  if (existing) return existing;
  try {
    const player = module.createAudioPlayer(SOURCES[name]);
    const loaded: LoadedPlayer = {
      play: () => {
        try {
          player.seekTo(0);
          player.play();
        } catch {
          // A failed cue must never break the flow that triggered it.
        }
      },
    };
    players.set(name, loaded);
    return loaded;
  } catch {
    return null;
  }
}

/** Lets tests and settings gate the cues without touching call sites. */
export function setOcmSoundsEnabled(value: boolean) {
  enabled = value;
}

export function areOcmSoundsEnabled() {
  return enabled;
}

/**
 * Plays an OCM cue. Safe from any context: web, missing native audio, or a
 * player that fails to build are all silently ignored. The sound only plays
 * while the app is on screen so it never doubles a system notification.
 */
export async function playOcmSound(name: OcmSoundName) {
  if (!enabled || Platform.OS === 'web') return;
  if (AppState.currentState !== 'active') return;

  const module = await getAudioModuleAsync();
  if (!module) return;

  const player = getPlayer(name, module);
  player?.play();
}

/**
 * True when an error message looks like a rate limit, quota or "too many
 * requests" rejection; used to pick the quota cue for prompt failures.
 */
export function isQuotaLikeMessage(message: string | undefined) {
  if (!message) return false;
  return /rate.?limit|quota|too many requests|429|exceeded your|limit reached|capacity/i.test(message);
}
