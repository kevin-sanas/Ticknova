export const DESIGN_TOKENS = Object.freeze({
  primary: '#365b80',
  canvas: '#f2f0ed'
})

export function hexToCssRgb(value) {
  if (!/^#[0-9a-f]{6}$/i.test(value)) throw new Error('Expected a six-digit CSS hex color.')
  const number = Number.parseInt(value.slice(1), 16)
  return `rgb(${number >> 16}, ${(number >> 8) & 255}, ${number & 255})`
}
