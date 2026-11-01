import type { LocationQueryValue } from 'vue-router'

/** Select one query value without converting large string IDs to numbers. */
export function queryValue(value: LocationQueryValue | LocationQueryValue[] | undefined): string | undefined {
  return (Array.isArray(value) ? value[0] : value) ?? undefined
}
