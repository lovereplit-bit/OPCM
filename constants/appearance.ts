// Appearance preferences shared by the persisted preference model and the
// palette. Kept free of React Native imports so parsing tests can load it
// without a UI runtime.

export type ColorScheme = 'light' | 'dark';

export type ThemeMode = 'system' | 'light' | 'dark';
export const THEME_MODES: readonly ThemeMode[] = ['system', 'light', 'dark'];
export const DEFAULT_THEME_MODE: ThemeMode = 'system';

export type AccentColor = 'gray' | 'green' | 'blue' | 'red' | 'purple' | 'pink';
export const ACCENT_COLORS: readonly AccentColor[] = ['gray', 'green', 'blue', 'red', 'purple', 'pink'];
// The OCM look is the monochrome grey scale, so gray is the default accent.
export const DEFAULT_ACCENT_COLOR: AccentColor = 'gray';
