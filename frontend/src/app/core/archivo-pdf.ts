/** Mismo límite que Nginx (100 MB). */
export const PDF_MAX_BYTES = 100 * 1024 * 1024;

/** null = archivo válido; string = mensaje de error para mostrar al usuario. */
export function validarArchivoPdf(file: File | null | undefined): string | null {
  if (!file) return 'Selecciona un archivo PDF.';
  if (file.size <= 0) return 'El archivo está vacío.';
  if (file.size > PDF_MAX_BYTES) return 'El archivo no puede superar 100 MB.';
  const nombre = file.name.trim().toLowerCase();
  if (!nombre.endsWith('.pdf')) return 'Solo se permiten archivos PDF.';
  const tipo = (file.type || '').toLowerCase();
  if (tipo && tipo !== 'application/pdf' && tipo !== 'application/x-pdf') {
    return 'Solo se permiten archivos PDF.';
  }
  return null;
}

export function aplicarValidacionPdfInput(
  input: HTMLInputElement,
  file: File | null | undefined,
  onOk: (file: File) => void,
  onError: (msg: string) => void,
  onClear: () => void,
): void {
  if (!file) {
    onClear();
    return;
  }
  const err = validarArchivoPdf(file);
  if (err) {
    input.value = '';
    onClear();
    onError(err);
    return;
  }
  onOk(file);
}
