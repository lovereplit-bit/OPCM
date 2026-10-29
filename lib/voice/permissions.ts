import * as Linking from 'expo-linking';
import { ExpoSpeechRecognitionModule } from 'expo-speech-recognition';

export type VoiceInputPermission = {
  granted: boolean;
  canAskAgain: boolean;
  restricted: boolean;
  available: boolean;
};

const UNAVAILABLE: VoiceInputPermission = { granted: false, canAskAgain: false, restricted: false, available: false };

function toPermission(result: { granted?: boolean; canAskAgain?: boolean; restricted?: boolean } | undefined): VoiceInputPermission {
  if (!result) {
    return UNAVAILABLE;
  }

  return {
    granted: Boolean(result.granted),
    canAskAgain: Boolean(result.canAskAgain),
    restricted: Boolean(result.restricted),
    available: true,
  };
}

/**
 * Reads the current microphone/speech-recognition permission without prompting.
 * Safe to call on mount; voice setup stays optional.
 */
export async function getVoiceInputPermissionAsync(): Promise<VoiceInputPermission> {
  try {
    return toPermission(await ExpoSpeechRecognitionModule.getPermissionsAsync());
  } catch {
    return UNAVAILABLE;
  }
}

/**
 * Prompts for microphone/speech-recognition permission. Called only from an
 * explicit "Enable voice input" action, never automatically.
 */
export async function requestVoiceInputPermissionAsync(): Promise<VoiceInputPermission> {
  try {
    return toPermission(await ExpoSpeechRecognitionModule.requestPermissionsAsync());
  } catch {
    return UNAVAILABLE;
  }
}

/**
 * Opens the app's own system settings page, where microphone and speech
 * recognition access can be restored after a denial. iOS has no supported deep
 * link into Siri or Dictation settings, so those remain text guidance.
 */
export async function openVoiceSettingsAsync(): Promise<void> {
  await Linking.openSettings().catch(() => undefined);
}
