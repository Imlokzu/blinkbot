type EffortModel = { efforts?: readonly string[]; effort_default?: string } | undefined;

/** Only this model's verified profile can supply selectable effort levels. */
export function effortProfile(model: EffortModel, saved: string) {
  const levels = [...new Set((model?.efforts ?? []).filter(level => typeof level === 'string' && level.length > 0))];
  const fallback = model?.effort_default ?? '';
  return {
    levels,
    thinking: levels.includes(saved) ? saved : levels.includes(fallback) ? fallback : '',
  };
}
