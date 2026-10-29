import { describe, expect, it, vi } from 'vitest';

import {
  ACCENT_COLORS,
  ACCENT_PALETTES,
  Colors,
  getColors,
} from '@/constants/theme';

vi.mock('react-native', () => ({
  Platform: { OS: 'web', select: (value: Record<string, unknown>) => value.web ?? value.default },
}));

function luminance(hex: string) {
  const channels = [1, 3, 5].map((index) => parseInt(hex.slice(index, index + 2), 16) / 255);
  const [r, g, b] = channels.map((value) => value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

function contrast(foreground: string, background: string) {
  const [light, dark] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return (light + 0.05) / (dark + 0.05);
}

describe('theme palette presets', () => {
  it('exposes the six expected themes in a stable order with gray first', () => {
    expect(ACCENT_COLORS).toEqual(['gray', 'green', 'blue', 'red', 'purple', 'pink']);
  });

  it('keeps gray identical to the base palette (the pure OCM monochrome)', () => {
    expect(getColors('light', 'gray')).toEqual(Colors.light);
    expect(getColors('dark', 'gray')).toEqual(Colors.dark);
  });

  it.each(ACCENT_COLORS)('defines full surface and accent tokens for %s', (accent) => {
    for (const scheme of ['light', 'dark'] as const) {
      const palette = getColors(scheme, accent);
      const tokens = ACCENT_PALETTES[accent][scheme];
      expect(palette.tint).toBe(tokens.tint);
      expect(palette.accent).toBe(tokens.accent);
      expect(palette.background).toBe(tokens.background);
      expect(palette.surface).toBe(tokens.surface);
      expect(palette.surfaceAlt).toBe(tokens.surfaceAlt);
      expect(palette.card).toBe(tokens.card);
      expect(palette.border).toBe(tokens.border);
      expect(palette.muted).toBe(tokens.muted);
      expect(palette.bubbleAssistant).toBe(tokens.bubbleAssistant);
      expect(palette.bubbleUser).toBe(tokens.bubbleUser);
      expect(palette.tabBackground).toBe(tokens.tabBackground);
      expect(palette.code.background).toBe(tokens.surfaceAlt);
      expect(palette.code.border).toBe(tokens.border);
      expect(palette.code.syntax).toEqual(Colors[scheme].code.syntax);
    }
  });

  it.each(ACCENT_COLORS)('keeps the OCM neutral surfaces for %s', (accent) => {
    // The OCM grey scale is the design system: every accent preset rides the
    // same neutral background/surface/border stack, only the tint and the
    // user bubble re-color.
    for (const scheme of ['light', 'dark'] as const) {
      const palette = getColors(scheme, accent);
      expect(palette.background).toBe(Colors[scheme].background);
      expect(palette.surfaceAlt).toBe(Colors[scheme].surfaceAlt);
      expect(palette.border).toBe(Colors[scheme].border);
      expect(palette.muted).toBe(Colors[scheme].muted);
      expect(palette.bubbleAssistant).toBe(Colors[scheme].bubbleAssistant);
    }
  });

  it.each(ACCENT_COLORS)('keeps text readable across the %s palettes', (accent) => {
    for (const scheme of ['light', 'dark'] as const) {
      const palette = getColors(scheme, accent);
      expect(contrast(palette.text, palette.background)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(palette.text, palette.surface)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(palette.text, palette.surfaceAlt)).toBeGreaterThanOrEqual(4.5);
      expect(contrast(palette.onBubbleUser, palette.bubbleUser)).toBeGreaterThanOrEqual(4.5);
    }
  });
});
