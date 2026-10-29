import { Platform } from 'react-native';
import type { MarkdownStyle } from 'react-native-enriched-markdown';

import { DEFAULT_ACCENT_COLOR, type AccentColor, type ColorScheme } from '@/constants/appearance';

export { ACCENT_COLORS, DEFAULT_ACCENT_COLOR, DEFAULT_THEME_MODE, THEME_MODES } from '@/constants/appearance';
export type { AccentColor, ColorScheme, ThemeMode } from '@/constants/appearance';

// The OpenCode grey scale, taken from the OCM app palette: a desktop-grade
// monochrome light/dark pair with a near-black icon background. The accent
// presets below reuse the same neutral surfaces and only re-tint interactive
// elements, so every preset keeps the OCM look while the "gray" preset is the
// pure monochrome default.
const ocmLightSurfaces = {
  background: '#FAFAFA',
  surface: '#FFFFFF',
  surfaceAlt: '#F0F0F0',
  card: '#FFFFFF',
  border: '#DCDCDC',
  muted: '#5C5C5C',
  icon: '#8A8A8A',
  bubbleAssistant: '#F0F0F0',
  tabBackground: '#FAFAFA',
  tabIconDefault: '#8A8A8A',
};

const ocmDarkSurfaces = {
  background: '#161616',
  surface: '#202020',
  surfaceAlt: '#202020',
  card: '#202020',
  border: '#2E2E2E',
  muted: '#AEAEAE',
  icon: '#808080',
  bubbleAssistant: '#202020',
  tabBackground: '#161616',
  tabIconDefault: '#808080',
};

type CodeSyntaxPalette = NonNullable<NonNullable<MarkdownStyle['codeBlock']>['syntaxColors']>;

export type CodePalette = {
  background: string;
  border: string;
  syntax: CodeSyntaxPalette;
};

// Code surfaces sit on the OCM layer color; syntax tokens keep GitHub's light
// and dark token colors so Markdown stays readable.
const lightCodePalette: CodePalette = {
  background: '#F0F0F0',
  border: '#DCDCDC',
  syntax: {
    keyword: '#CF222E',
    string: '#0A3069',
    number: '#0550AE',
    constant: '#0550AE',
    comment: '#6E7781',
    function: '#8250DF',
    type: '#953800',
    property: '#0550AE',
    tag: '#116329',
    attribute: '#0550AE',
  },
};

const darkCodePalette: CodePalette = {
  background: '#202020',
  border: '#2E2E2E',
  syntax: {
    keyword: '#FF7B72',
    string: '#A5D6FF',
    number: '#79C0FF',
    constant: '#79C0FF',
    comment: '#8B949E',
    function: '#D2A8FF',
    type: '#FFA657',
    property: '#79C0FF',
    tag: '#7EE787',
    attribute: '#79C0FF',
  },
};

// The pure OCM monochrome preset: ink-on-paper in light, paper-on-ink in dark.
const grayLightTokens = {
  tint: '#161616',
  accent: '#5C5C5C',
  bubbleUser: '#161616',
  onBubbleUser: '#FAFAFA',
  ...ocmLightSurfaces,
};

const grayDarkTokens = {
  tint: '#F2F2F2',
  accent: '#AEAEAE',
  bubbleUser: '#F2F2F2',
  onBubbleUser: '#161616',
  ...ocmDarkSurfaces,
};

export const Colors = {
  light: {
    text: '#161616',
    ...grayLightTokens,
    success: '#2E7D32',
    warning: '#A56A0D',
    danger: '#B3261E',
    onBubbleAssistant: '#161616',
    code: lightCodePalette,
  },
  dark: {
    text: '#F2F2F2',
    ...grayDarkTokens,
    success: '#8FD694',
    warning: '#E6B35A',
    danger: '#F2B8B5',
    onBubbleAssistant: '#F2F2F2',
    code: darkCodePalette,
  },
};

export type Palette = typeof Colors.light;

// Each preset keeps the OCM neutral surfaces, borders and chat bubbles; only
// the tint/accent and the user bubble re-color, so the whole app stays in the
// OCM grey-scale style whatever accent the user picks in Settings.
type ThemeTokens = Pick<
  Palette,
  | 'accent'
  | 'background'
  | 'border'
  | 'bubbleAssistant'
  | 'bubbleUser'
  | 'card'
  | 'icon'
  | 'muted'
  | 'onBubbleUser'
  | 'surface'
  | 'surfaceAlt'
  | 'tabBackground'
  | 'tabIconDefault'
  | 'tint'
>;

function accentLight(tint: string, bubbleUser: string, onBubbleUser: string): ThemeTokens {
  return { tint, accent: tint, bubbleUser, onBubbleUser, ...ocmLightSurfaces };
}

function accentDark(tint: string, bubbleUser: string, onBubbleUser: string): ThemeTokens {
  return { tint, accent: tint, bubbleUser, onBubbleUser, ...ocmDarkSurfaces };
}

export const ACCENT_PALETTES: Record<AccentColor, Record<ColorScheme, ThemeTokens>> = {
  gray: {
    light: grayLightTokens,
    dark: grayDarkTokens,
  },
  green: {
    light: accentLight('#0F8A6C', '#135C4E', '#F5FFFB'),
    dark: accentDark('#7AE7C0', '#1C6B5C', '#F3FFFB'),
  },
  blue: {
    light: accentLight('#1F6FB2', '#14486B', '#F4FAFF'),
    dark: accentDark('#7CC4FF', '#1B5B82', '#F2FAFF'),
  },
  red: {
    light: accentLight('#B03A48', '#6E222C', '#FFF5F6'),
    dark: accentDark('#FF9A9A', '#7A2E35', '#FFF5F6'),
  },
  purple: {
    light: accentLight('#7A4BC4', '#4A2A78', '#F8F4FF'),
    dark: accentDark('#C9A8FF', '#553A85', '#F8F4FF'),
  },
  pink: {
    light: accentLight('#B93E78', '#75264B', '#FFF4F9'),
    dark: accentDark('#FFA6CF', '#7E3560', '#FFF4F9'),
  },
};

export function getColors(scheme: ColorScheme, accent: AccentColor = DEFAULT_ACCENT_COLOR): Palette {
  const palette = { ...Colors[scheme], ...ACCENT_PALETTES[accent][scheme] };
  return {
    ...palette,
    // Code surfaces follow the themed neutral surfaces while the syntax token
    // colors stay in the shared GitHub light/dark palettes.
    code: {
      ...Colors[scheme].code,
      background: palette.surfaceAlt,
      border: palette.border,
    },
  };
}

export const Fonts = Platform.select({
  ios: {
    sans: 'Avenir Next',
    mono: 'ui-monospace',
    display: 'Avenir Next',
  },
  default: {
    sans: 'normal',
    mono: 'monospace',
    display: 'normal',
  },
  web: {
    sans: "'Avenir Next', 'Segoe UI', 'Trebuchet MS', sans-serif",
    mono: "SFMono-Regular, Menlo, Monaco, Consolas, 'Liberation Mono', 'Courier New', monospace",
    display: "'Avenir Next', 'Segoe UI', 'Trebuchet MS', sans-serif",
  },
});
