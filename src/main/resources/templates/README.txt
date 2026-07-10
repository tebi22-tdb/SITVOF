Plantillas de los Anexos 9.1, 9.2 y 9.3
========================================

Los tres se generan igual: plantilla XHTML en templates/html/*.html,
renderizada a PDF con OpenHTMLtoPDF (HtmlAnexoPdfService). No requieren
LibreOffice ni conversión de .docx.

Anexo 9.1 — ITVO-AC-PR-05-02 — templates/html/anexo-9-1.html
Anexo 9.2 — ITVO-AC-PR-05-03 — templates/html/anexo-9-2.html (constancia de no inconveniencia)
Anexo 9.3 — ITVO-AC-PR-05-04 — templates/html/anexo-9-3.html (aviso de realización del acto)

Los placeholders se escriben como {{NOMBRE}} y se reemplazan en
EgresadoService.kt (construirValoresPlantillaHtml y los extras de cada
crearAnexoXX). Si no hay valor para un placeholder, HtmlAnexoPdfService lo
sustituye por "—".

Los .docx que quedan en esta carpeta (anexo-9-1-plantilla.docx,
anexo-9-3.docx, etc.) son solo material de referencia del formato original;
ya no se usan para generar los PDF.
