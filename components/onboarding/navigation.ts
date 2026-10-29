export const ONBOARDING_STEPS = ['index', 'permissions', 'setup', 'connect', 'ai', 'project', 'general', 'voice', 'ready'] as const;
export type OnboardingStepName = typeof ONBOARDING_STEPS[number];

/**
 * The flow the first run walks. On Android the app sets itself up: the local
 * Linux server step ("setup") replaces the manual "connect" step. The manual
 * step stays available on other platforms, and remains reachable from the
 * setup screen for connecting to an external server instead.
 *
 * [localSupported] comes from the caller so this module stays pure and
 * testable without a React Native runtime.
 */
export function getOnboardingSteps(hasConnection: boolean, localSupported = false) {
  const selfHostedStep: OnboardingStepName = localSupported ? 'setup' : 'connect'
  const flow = ONBOARDING_STEPS.filter((step) => (step === 'setup' || step === 'connect') ? step === selfHostedStep : true)
  return flow.filter((step) => hasConnection || (step !== 'ai' && step !== 'project'))
}

export function onboardingRoute(step: OnboardingStepName) {
  return step === 'index' ? '/onboarding' : `/onboarding/${step}` as const
}
