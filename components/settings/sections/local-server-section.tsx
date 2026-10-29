import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Chip, Dialog, List, Portal, Text } from 'react-native-paper';

import { SettingButton, SettingSwitchRow, SettingTextInput } from './setting-rows';
import { SettingTarget } from '../settings-panel';
import { Fonts } from '@/constants/theme';
import { usePalette } from '@/hooks/use-palette';
import {
  getLocalServerInfo,
  getLocalServerLogs,
  isLocalServerSupported,
  restartLocalServer,
  setLocalServerApiKey,
  setLocalServerCompatMode,
  stopLocalServer,
  subscribeLocalServer,
  type LocalServerInfo,
} from '@/lib/local-server';

/**
 * Management surface for the phone's own OpenCode server: live state, the
 * generated identity, the optional personal API key, compatibility mode and
 * the raw logs.
 */
export function LocalServerSection() {
  const { t } = useTranslation();
  const palette = usePalette();
  const [info, setInfo] = useState<LocalServerInfo>(() => getLocalServerInfo());
  const [apiKeyDraft, setApiKeyDraft] = useState('');
  const [apiKeySaved, setApiKeySaved] = useState(false);
  const [logs, setLogs] = useState('');
  const [logsOpen, setLogsOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const unsubscribe = subscribeLocalServer((event) => {
      setInfo((current) => ({ ...current, ...event }));
    });
    setInfo(getLocalServerInfo());
    return unsubscribe;
  }, []);

  useEffect(() => {
    if (!apiKeySaved) setApiKeyDraft(getLocalServerInfo().apiKey ?? '');
  }, [apiKeySaved]);

  const supported = isLocalServerSupported();

  const summaryKey = !supported
    ? 'unsupported'
    : info.phase === 'running'
      ? 'running'
      : info.phase === 'starting'
        ? 'starting'
        : info.phase === 'downloading'
          ? 'downloading'
          : info.phase === 'installing'
            ? 'installing'
            : info.phase === 'failed'
              ? 'failed'
              : 'stopped';

  const restart = useCallback(() => {
    setBusy(true);
    void restartLocalServer()
      .then((snapshot) => setInfo(snapshot))
      .catch(() => undefined)
      .finally(() => setBusy(false));
  }, []);

  const stop = useCallback(() => {
    setBusy(true);
    void stopLocalServer()
      .then((snapshot) => setInfo(snapshot))
      .catch(() => undefined)
      .finally(() => setBusy(false));
  }, []);

  const saveApiKey = useCallback(() => {
    setBusy(true);
    void setLocalServerApiKey(apiKeyDraft.trim() || null)
      .then((snapshot) => {
        setInfo(snapshot);
        setApiKeySaved(true);
        setTimeout(() => setApiKeySaved(false), 4000);
      })
      .catch(() => undefined)
      .finally(() => setBusy(false));
  }, [apiKeyDraft]);

  const toggleCompat = useCallback((enabled: boolean) => {
    void setLocalServerCompatMode(enabled)
      .then((snapshot) => setInfo(snapshot))
      .catch(() => undefined);
  }, []);

  const openLogs = useCallback(() => {
    void getLocalServerLogs().then((text) => {
      setLogs(text);
      setLogsOpen(true);
    });
  }, []);

  if (!supported) {
    return (
      <View style={styles.section}>
        <Text variant="titleLarge" style={[styles.title, { color: palette.text }]}>{t('settings:localServer.title')}</Text>
        <List.Item title={t('settings:localServer.title')} description={t('settings:localServer.summary.unsupported')} />
      </View>
    );
  }

  return (
    <View style={styles.section}>
      <Text variant="titleLarge" style={[styles.title, { color: palette.text }]}>{t('settings:localServer.title')}</Text>
      <Text variant="bodyMedium" style={[styles.description, { color: palette.muted }]}>{t('settings:localServer.description')}</Text>

      <List.Item
        title={t('settings:localServer.title')}
        description={t(`settings:localServer.summary.${summaryKey}`, { port: info.port > 0 ? info.port : 4096 })}
        left={(props) => <List.Icon {...props} icon="server" color={palette.tint} />}
        right={() => <Chip compact>{info.version || '—'}</Chip>}
      />
      <List.Item
        title={t('settings:localServer.identity.nick')}
        description={info.nick || '—'}
        left={(props) => <List.Icon {...props} icon="identifier" color={palette.tint} />}
      />
      <List.Item
        title={t('settings:localServer.identity.address')}
        description={info.port > 0 ? `127.0.0.1:${info.port}` : '—'}
        left={(props) => <List.Icon {...props} icon="ip-network" color={palette.tint} />}
      />
      <List.Item
        title={t('settings:localServer.identity.password')}
        description={t('settings:localServer.identity.password')}
        descriptionNumberOfLines={2}
        left={(props) => <List.Icon {...props} icon="key-chain" color={palette.tint} />}
      />

      <View style={styles.actions}>
        <SettingButton mode="outlined" disabled={busy} onPress={restart} icon="refresh">{t('settings:localServer.restart')}</SettingButton>
        <SettingButton mode="outlined" disabled={busy} onPress={stop} textColor={palette.danger} icon="stop">{t('settings:localServer.stop')}</SettingButton>
      </View>

      <SettingTarget label={t('settings:localServer.apiKey.title')}>
        <View style={styles.apiKeyBlock}>
          <Text variant="bodyLarge" style={{ color: palette.text }}>{t('settings:localServer.apiKey.title')}</Text>
          <Text variant="bodyMedium" style={{ color: palette.muted }}>{t('settings:localServer.apiKey.description')}</Text>
          <SettingTextInput
            placeholder={t('settings:localServer.apiKey.placeholder')}
            value={apiKeyDraft}
            onChangeText={setApiKeyDraft}
            autoCapitalize="none"
            autoCorrect={false}
            secureTextEntry
            style={styles.apiKeyInput}
          />
          <SettingButton mode="contained" disabled={busy} onPress={saveApiKey}>
            {apiKeySaved ? t('settings:localServer.apiKey.saved') : t('common:actions.save')}
          </SettingButton>
          {apiKeySaved ? (
            <Text variant="bodySmall" style={{ color: palette.success }}>{t('settings:localServer.apiKey.saved')}</Text>
          ) : null}
        </View>
      </SettingTarget>

      <SettingSwitchRow
        title={t('settings:localServer.compat.title')}
        description={t('settings:localServer.compat.description')}
        palette={palette}
        value={info.compat}
        onValueChange={toggleCompat}
      />

      <SettingButton mode="text" textColor={palette.muted} onPress={() => void openLogs()} icon="text-box-outline">
        {t('settings:localServer.logs')}
      </SettingButton>

      <Portal>
        <Dialog visible={logsOpen} onDismiss={() => setLogsOpen(false)}>
          <Dialog.Title>{t('settings:localServer.logsTitle')}</Dialog.Title>
          <Dialog.ScrollArea>
            <ScrollView style={styles.logScroll}>
              <Text variant="bodySmall" style={styles.logText} selectable>
                {logs || t('settings:localServer.logsEmpty')}
              </Text>
            </ScrollView>
          </Dialog.ScrollArea>
          <Dialog.Actions>
            <SettingButton onPress={() => setLogsOpen(false)}>{t('common:actions.close')}</SettingButton>
          </Dialog.Actions>
        </Dialog>
      </Portal>
    </View>
  );
}

const styles = StyleSheet.create({
  section: { gap: 4, paddingBottom: 16 },
  title: { fontFamily: Fonts.display, fontWeight: '700' },
  description: { paddingBottom: 8 },
  actions: { flexDirection: 'row', flexWrap: 'wrap', gap: 10, paddingVertical: 8 },
  apiKeyBlock: { gap: 10, paddingBottom: 8 },
  apiKeyInput: { minHeight: 48 },
  logScroll: { maxHeight: 400, paddingHorizontal: 8 },
  logText: { fontFamily: Fonts.mono, fontSize: 11 },
});
