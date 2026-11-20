import { mensajeFallaConexion, mensajeApiHumano } from './mensaje-conexion';

/**
 * Con HttpClient y responseType: 'blob', los errores 4xx/5xx suelen traer el JSON del API en err.error como Blob.
 */
export async function mensajeErrorApiConBlob(err: unknown, fallback: string): Promise<string> {
  const red = mensajeFallaConexion(err);
  if (red) return red;
  if (err && typeof err === 'object' && 'error' in err) {
    const er = (err as { error?: unknown }).error;
    if (er instanceof Blob) {
      try {
        const txt = await er.text();
        const j = JSON.parse(txt) as { error?: string };
        const msg = j?.error?.trim();
        if (msg) return mensajeApiHumano({ error: { error: msg } }) ?? fallback;
      } catch {
        /* ignorar */
      }
      return fallback;
    }
    const humano = mensajeApiHumano(err);
    if (humano) return humano;
  }
  return fallback;
}
