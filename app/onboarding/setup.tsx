import { MaterialCommunityIcons } from '@expo/vector-icons';
import { Image, ScrollView, StyleSheet, View } from 'react-native';
import { Button, Dialog, Portal, ProgressBar, Text } from 'react-native-paper';
import { useRouter } from 'expo-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';

import { OnboardingStep } from '@/components/onboarding/onboarding-step';
import { useOnboardingNavigation } from '@/components/onboarding/use-onboarding-navigation';
import { useConnection } from '@/providers/opencode-contexts';
import { usePalette } from '@/hooks/use-palette';
import { Fonts } from '@/constants/theme';
import {
  ensureLocalServerProfile,
  getLocalServerInfo,
  getLocalServerLogs,
  isLocalServerSupported,
  restartLocalServer,
  startLocalServer,
  subscribeLocalServer,
  type LocalServerEvent,
  type LocalServerInfo,
} from '@/lib/local-server';

/**
 * The self-setup step: the app downloads its Linux runtime, starts the
 * OpenCode server on the phone's loopback and creates the connection profile
 * with the generated password and nickname — nobody enters anything by hand.
 */

/** How long the wizard waits for the connect step before surfacing an error. */
const CONNECT_TIMEOUT_MS = 60_000;

/** Rejects with [message] when [promise] is still pending after [timeoutMs]. */
function withConnectTimeout<T>(promise: Promise<T>, timeoutMs: number, message: string): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(message)), timeoutMs);

    promise.then(
      (value) => { clearTimeout(timer); resolve(value); },
      (reason) => { clearTimeout(timer); reject(reason); },
    );
  });
}

export default function OnboardingSetupScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  const palette = usePalette();
  const navigation = useOnboardingNavigation('setup');
  const { switchConnection } = useConnection();
  const [info, setInfo] = useState<LocalServerInfo>(() => getLocalServerInfo());
  const [logs, setLogs] = useState('');
  const [logsOpen, setLogsOpen] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [connectError, setConnectError] = useState<string>();
  const attemptedRef = useRef(false);
  const connectAttemptedRef = useRef(false);

  const phase = info.phase;
  const busy = phase === 'downloading' || phase === 'installing' || phase === 'starting' || connecting;
  const progressValue = info.progress >= 0 ? info.progress / 100 : undefined;

  // Reflect native server publishes into the screen state.
  useEffect(() => {
    const unsubscribe = subscribeLocalServer((event: LocalServerEvent) => {
      setInfo((current) => ({
        ...current,
        ...event,
        // A failed publish carries no port of its own; keep the last known
        // one so the address row never blanks out mid-setup.
        port: event.port > 0 ? event.port : current.port,
      }));
    });
    setInfo(getLocalServerInfo());
    return unsubscribe;
  }, []);

  // Kick off the server once per visit.
  useEffect(() => {
    if (attemptedRef.current) return;
    attemptedRef.current = true;
    void startLocalServer()
      .then((snapshot) => setInfo(snapshot))
      .catch((reason: unknown) => {
        setInfo((current) => ({
          ...current,
          phase: 'failed',
          message: reason instanceof Error ? reason.message : String(reason),
        }));
      });
  }, []);

  // When the server is up, create the profile and connect. The run is
  // guarded by a ref, not by the effect lifecycle: switchConnection gets a new
  // identity while it runs (the catalog load inside it updates state that hook
  // depends on), and a re-run of this effect would mark the in-flight connect
  // cancelled, silently drop its result and leave `connecting` stuck on with
  // the buttons grayed out forever.
  useEffect(() => {
    if (phase !== 'running' || connectAttemptedRef.current) return;
    connectAttemptedRef.current = true;
    setConnecting(true);
    setConnectError(undefined);

    void (async () => {
      try {
        const snapshot = getLocalServerInfo();
        const credentials = await ensureLocalServerProfile(snapshot);
        const result = await withConnectTimeout(
          switchConnection({
            serverUrl: credentials.serverUrl,
            username: credentials.username,
            password: credentials.password,
          }),
          CONNECT_TIMEOUT_MS,
          t('onboarding:setup.errors.timeout'),
        );
        if (result.status !== 'connected') {
          throw new Error(result.message || t('onboarding:setup.errors.connect'));
        }
        requestAnimationFrame(() => router.push('/onboarding/ai'));
      } catch (reason: unknown) {
        setConnectError(reason instanceof Error ? reason.message : String(reason));
        // The server itself may still be healthy: a retry, or the next healthy
        // phase after a restart, must be allowed to connect again.
        connectAttemptedRef.current = false;
      } finally {
        setConnecting(false);
      }
    })();
  }, [phase, router, switchConnection, t]);

  const retry = useCallback(() => {
    setConnectError(undefined);
    void restartLocalServer()
      .then((snapshot) => setInfo(snapshot))
      .catch(() => undefined);
  }, []);

  const openLogs = useCallback(() => {
    void getLocalServerLogs().then((text) => {
      setLogs(text);
      setLogsOpen(true);
    });
  }, []);

  const statusText = connectError
    ? t('onboarding:setup.status.failedConnect')
    : phase === 'downloading'
      ? t('onboarding:setup.status.downloading')
      : phase === 'installing'
        ? t('onboarding:setup.status.installing')
        : phase === 'starting'
          ? t('onboarding:setup.status.starting')
          : phase === 'running'
            ? t('onboarding:setup.status.running')
            : phase === 'failed'
              ? t('onboarding:setup.status.failed')
              : t('onboarding:setup.status.preparing');

  return (
    <OnboardingStep
      step="setup"
      title={t('onboarding:setup.title')}
      subtitle={t('onboarding:setup.subtitle')}
      testID="onboarding-setup"
      backDisabled={busy}
      scrollEnabled={false}
      footer={
        <>
          <Button
            disabled={busy}
            testID="onboarding-setup-external"
            onPress={() => router.push('/onboarding/connect')}
            textColor={palette.muted}>
            {t('onboarding:setup.otherServer')}
          </Button>
          <Button
            mode="outlined"
            disabled={busy}
            onPress={() => void retry()}
            icon={({ size, color }) => <MaterialCommunityIcons name="refresh" size={size} color={color} />}>
            {t('onboarding:setup.retry')}
          </Button>
        </>
      }>
      <ScrollView contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
        <View style={[styles.card, { backgroundColor: palette.surface, borderColor: palette.border }]}>
          <Image source={require('../../assets/images/splash-icon.png')} style={styles.logo} resizeMode="contain" />
          <Text variant="titleMedium" style={[styles.status, { color: palette.text }]}>
            {statusText}
          </Text>
          {phase === 'failed' || connectError ? (
            <Text variant="bodySmall" style={{ color: palette.danger }}>
              {connectError || info.message}
            </Text>
          ) : null}
          {progressValue === undefined && busy ? (
            <View style={styles.indeterminateWrap}>
              <ProgressBar indeterminate color={palette.tint} style={styles.bar} />
            </View>
          ) : progressValue !== undefined ? (
            <ProgressBar progress={progressValue} color={palette.tint} style={styles.bar} />
          ) : null}
          {phase === 'starting' && info.message ? (
            <Text variant="bodySmall" style={{ color: palette.muted }}>
              {info.message}
            </Text>
          ) : null}
          {busy ? (
            <Text variant="bodySmall" style={{ color: palette.muted }}>
              {phase === 'downloading' && info.progress >= 0
                ? t('onboarding:setup.progress', { percent: Math.floor(info.progress) })
                : t('onboarding:setup.hint')}
            </Text>
          ) : null}
        </View>

        <View style={[styles.identity, { backgroundColor: palette.surfaceAlt }]}>
          <View style={styles.identityRow}>
            <MaterialCommunityIcons name="identifier" size={18} color={palette.muted} />
            <Text variant="bodyMedium" style={{ color: palette.text }}>
              {t('onboarding:setup.identity.nick')}
            </Text>
            <Text variant="bodyMedium" style={[styles.identityValue, { color: palette.text }]}>
              {info.nick || '—'}
            </Text>
          </View>
          <View style={styles.identityRow}>
            <MaterialCommunityIcons name="server" size={18} color={palette.muted} />
            <Text variant="bodyMedium" style={{ color: palette.text }}>
              {t('onboarding:setup.identity.address')}
            </Text>
            <Text variant="bodyMedium" style={[styles.identityValue, { color: palette.muted }]}>
              {info.port > 0 ? `127.0.0.1:${info.port}` : '—'}
            </Text>
          </View>
          <View style={styles.identityRow}>
            <MaterialCommunityIcons name="key-chain" size={18} color={palette.muted} />
            <Text variant="bodyMedium" style={{ color: palette.text }}>
              {t('onboarding:setup.identity.password')}
            </Text>
            <Text variant="bodyMedium" style={[styles.identityValue, { color: palette.muted }]}>
              {t('onboarding:setup.identity.automatic')}
            </Text>
          </View>
          <Text variant="bodySmall" style={{ color: palette.muted }}>
            {t('onboarding:setup.identity.footnote')}
          </Text>
        </View>

        <Button
          mode="text"
          textColor={palette.muted}
          labelStyle={styles.logsButton}
          onPress={() => void openLogs()}>
          {t('onboarding:setup.showLogs')}
        </Button>
      </ScrollView>

      <Portal>
        <Dialog visible={logsOpen} onDismiss={() => setLogsOpen(false)}>
          <Dialog.Title>{t('onboarding:setup.logsTitle')}</Dialog.Title>
          <Dialog.ScrollArea>
            <ScrollView style={styles.logScroll}>
              <Text variant="bodySmall" style={styles.logText} selectable>
                {logs || t('onboarding:setup.logsEmpty')}
              </Text>
            </ScrollView>
          </Dialog.ScrollArea>
          <Dialog.Actions>
            <Button onPress={() => setLogsOpen(false)}>{t('common:actions.close')}</Button>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </OnboardingStep>
  );
}

const styles = StyleSheet.create({
  content: { gap: 14, paddingBottom: 24 },
  card: { alignItems: 'center', borderRadius: 12, borderWidth: 1, gap: 12, padding: 20 },
  logo: { height: 96, width: 96 },
  status: { fontFamily: Fonts.display, fontWeight: '600', textAlign: 'center' },
  bar: { width: '100%' },
  indeterminateWrap: { width: '100%' },
  identity: { borderRadius: 12, gap: 8, padding: 16 },
  identityRow: { alignItems: 'center', flexDirection: 'row', gap: 8 },
  identityValue: { fontFamily: Fonts.mono, flex: 1, textAlign: 'right' },
  logsButton: { fontSize: 13 },
  logScroll: { maxHeight: 360, paddingHorizontal: 8 },
  logText: { fontFamily: Fonts.mono, fontSize: 11 },
});
