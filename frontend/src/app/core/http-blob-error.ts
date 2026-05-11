/**
 * Con HttpClient y responseType: 'blob', los errores 4xx/5xx suelen traer el JSON del API en err.error como Blob.
 */
export async function mensajeErrorApiConBlob(err: unknown, fallback: string): Promise<string> {
  if (err && typeof err === 'object' && 'error' in err) {
    const er = (err as { error?: unknown }).error;
    if (er instanceof Blob) {
      try {
        const txt = await er.text();
        const j = JSON.parse(txt) as { error?: string };
        const msg = j?.error?.trim();
        if (msg) return msg;
      } catch {
        /* ignorar */
      }
      return fallback;
    }
    const oe = (err as { error?: { error?: string } }).error;
    if (oe && typeof oe === 'object' && typeof oe.error === 'string') {
      const msg = oe.error.trim();
      if (msg) return msg;
    }
  }
  return fallback;
}
