// A separate worker keeps login typing responsive while doing SHA-256 work.
self.onmessage = async ({ data }) => {
  try {
    const { challenge_id: id, bits, expires_in: ttl } = data
    if (typeof id !== 'string' || !Number.isInteger(bits) || bits < 16 || bits > 22) throw new Error('invalid challenge')
    const encoder = new TextEncoder()
    const deadline = Date.now() + Math.min(175, ttl - 5) * 1000
    const whole = Math.floor(bits / 8), remaining = bits % 8
    const valid = hash => {
      const bytes = new Uint8Array(hash)
      for (let i = 0; i < whole; i++) if (bytes[i] !== 0) return false
      return !remaining || bytes[whole] < 2 ** (8 - remaining)
    }
    for (let base = 0; Date.now() < deadline; base += 64) {
      const hashes = await Promise.all(Array.from({ length: 64 }, (_, i) => crypto.subtle.digest('SHA-256', encoder.encode(`${id}:${base + i}`))))
      for (let i = 0; i < hashes.length; i++) if (valid(hashes[i])) { self.postMessage({ nonce: String(base + i) }); return }
    }
    throw new Error('expired')
  } catch { self.postMessage({ error: true }) }
}
