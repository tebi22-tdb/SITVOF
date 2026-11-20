/**
 * Texto que ve quien usa la pagina cuando una peticion no llega o tarda.
 * Status 0, timeout, 504 y el mensaje interno de Angular no se muestran.
 */
export function mensajeFallaConexion(err: unknown): string | null {
  if (!esFallaDeRed(err)) return null;
  if (typeof navigator !== 'undefined' && navigator.onLine === false) return 'No hay conexión.';
  if (esConexionLenta(err)) return 'Conexión lenta.';
  return 'No hay conexión.';
}

/** Mensaje del sistema si se puede leer en pantalla. No usa el texto interno de la peticion. */
export function mensajeApiHumano(err: unknown): string | null {
  const candidatos = textosApi(err);
  for (const texto of candidatos) {
    const limpio = texto.trim();
    if (limpio && !esTextoTecnico(limpio)) return limpio;
  }
  return null;
}

/** Conexion: frase corta. Si el sistema mando un aviso claro, ese. Si no, el texto de respaldo. */
export function textoErrorUsuario(err: unknown, fallback: string, prefijo = ''): string {
  const red = mensajeFallaConexion(err);
  if (red) return red;
  const api = mensajeApiHumano(err);
  if (api) return prefijo ? `${prefijo}${api}` : api;
  return fallback;
}

function esFallaDeRed(err: unknown): boolean {
  if (esConexionLenta(err)) return true;
  const status = estadoHttp(err);
  if (status === 0) return true;
  const crudo = textoCrudo(err).toLowerCase();
  return (
    crudo.includes('unknown error') ||
    crudo.includes('failed to fetch') ||
    crudo.includes('networkerror') ||
    crudo.includes('network error') ||
    (crudo.includes('http failure') && /:\s*0\b/.test(crudo))
  );
}

function esConexionLenta(err: unknown): boolean {
  const nombre = nombreError(err);
  const status = estadoHttp(err);
  if (nombre === 'TimeoutError' || status === 408 || status === 504) return true;
  return textoCrudo(err).toLowerCase().includes('timeout');
}

function textosApi(err: unknown): string[] {
  if (!err || typeof err !== 'object') return [];
  const error = (err as { error?: unknown }).error;
  if (typeof error === 'string') return [error];
  if (!error || typeof error !== 'object' || error instanceof Blob) return [];
  const cuerpo = error as { error?: unknown; message?: unknown };
  const salida: string[] = [];
  if (typeof cuerpo.error === 'string') salida.push(cuerpo.error);
  if (typeof cuerpo.message === 'string') salida.push(cuerpo.message);
  return salida;
}

function esTextoTecnico(texto: string): boolean {
  const t = texto.toLowerCase();
  if (t.length > 180) return true;
  return (
    t.includes('<html') ||
    t.includes('<!doctype') ||
    t.includes('http failure') ||
    t.includes('unknown error') ||
    t.includes('failed to fetch') ||
    t.includes('networkerror') ||
    t.includes('internal server error') ||
    t.includes('bad gateway') ||
    t.includes('spring') ||
    t.includes('mongodb') ||
    t.includes('localhost') ||
    t.includes('npm start') ||
    t.includes('proxy') ||
    t.includes('8081') ||
    t.includes('exception') ||
    t.includes('nullpointer') ||
    t.includes('stacktrace') ||
    t.includes('/api/') ||
    t.includes('smtp') ||
    t.includes('base de datos') ||
    t.includes('logs') ||
    t.includes('plantilla') ||
    t.includes('segmento_') ||
    t.includes('id inválido') ||
    t.includes('id invalido') ||
    t.includes('no autenticado') ||
    t.includes('java.') ||
    t.includes('kotlin')
  );
}

function estadoHttp(err: unknown): number | null {
  if (!err || typeof err !== 'object' || !('status' in err)) return null;
  const status = (err as { status?: unknown }).status;
  return typeof status === 'number' ? status : null;
}

function nombreError(err: unknown): string {
  if (!err || typeof err !== 'object' || !('name' in err)) return '';
  const name = (err as { name?: unknown }).name;
  return typeof name === 'string' ? name : '';
}

function textoCrudo(err: unknown): string {
  if (!err || typeof err !== 'object' || !('message' in err)) return '';
  const message = (err as { message?: unknown }).message;
  return typeof message === 'string' ? message : '';
}
