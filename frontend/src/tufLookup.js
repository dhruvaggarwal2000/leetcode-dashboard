import tufMap from './tuf-mapping.json'

const bySlug = Object.fromEntries(tufMap.map(e => [e.slug, e]))

function slugFrom(url) {
  return url?.split('/problems/')[1]?.replace(/\/$/, '')
}

export function tufLookup(leetcodeUrl) {
  return bySlug[slugFrom(leetcodeUrl)] ?? null
}
