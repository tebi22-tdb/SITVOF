import {
  Component,
  ElementRef,
  Input,
  OnChanges,
  OnDestroy,
  SimpleChanges,
  ViewChild,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { DomSanitizer, SafeResourceUrl } from '@angular/platform-browser';
import * as pdfjsLib from 'pdfjs-dist';
import type { PDFDocumentProxy, RenderTask } from 'pdfjs-dist';

pdfjsLib.GlobalWorkerOptions.workerSrc = '/assets/pdfjs/pdf.worker.min.js';

type ModoVisor = 'pdfjs' | 'iframe';

@Component({
  selector: 'app-pdf-viewer-panel',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './pdf-viewer-panel.component.html',
  styleUrl: './pdf-viewer-panel.component.css',
})
export class PdfViewerPanelComponent implements OnChanges, OnDestroy {
  @Input() src: string | null = null;
  @Input() reloadToken = 0;
  @Input() fileName = 'documento.pdf';

  @ViewChild('root') rootRef?: ElementRef<HTMLElement>;
  @ViewChild('canvas') canvasRef?: ElementRef<HTMLCanvasElement>;
  @ViewChild('viewport') viewportRef?: ElementRef<HTMLDivElement>;

  loading = false;
  error = '';
  pageNum = 1;
  pageCount = 0;
  scale = 1;
  fitWidth = true;
  modo: ModoVisor = 'pdfjs';
  iframeUrlSeguro: SafeResourceUrl | null = null;

  private pdfDoc: PDFDocumentProxy | null = null;
  private renderTask: RenderTask | null = null;
  private loadGen = 0;
  private resizeObserver: ResizeObserver | null = null;
  private pdfData: ArrayBuffer | null = null;

  constructor(private sanitizer: DomSanitizer) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['src'] || changes['reloadToken']) {
      void this.loadPdf();
    }
  }

  ngOnDestroy(): void {
    this.resizeObserver?.disconnect();
    this.cancelRender();
    void this.pdfDoc?.destroy();
    this.pdfDoc = null;
    this.pdfData = null;
  }

  paginaAnterior(): void {
    if (this.modo !== 'pdfjs' || this.pageNum <= 1) return;
    this.pageNum--;
    void this.renderPage();
  }

  paginaSiguiente(): void {
    if (this.modo !== 'pdfjs' || this.pageNum >= this.pageCount) return;
    this.pageNum++;
    void this.renderPage();
  }

  zoomMenos(): void {
    if (this.modo !== 'pdfjs') return;
    this.fitWidth = false;
    this.scale = Math.max(0.5, Math.round((this.scale - 0.15) * 100) / 100);
    void this.renderPage();
  }

  zoomMas(): void {
    if (this.modo !== 'pdfjs') return;
    this.fitWidth = false;
    this.scale = Math.min(3, Math.round((this.scale + 0.15) * 100) / 100);
    void this.renderPage();
  }

  ajustarAncho(): void {
    if (this.modo !== 'pdfjs') return;
    this.fitWidth = true;
    void this.renderPage();
  }

  async pantallaCompleta(): Promise<void> {
    const el = this.rootRef?.nativeElement;
    if (!el) return;
    if (!document.fullscreenElement) {
      await el.requestFullscreen?.();
    } else {
      await document.exitFullscreen?.();
    }
  }

  descargar(): void {
    if (!this.src) return;
    const a = document.createElement('a');
    a.href = this.src;
    a.download = this.fileName || 'documento.pdf';
    a.click();
  }

  private async loadPdf(): Promise<void> {
    const gen = ++this.loadGen;
    this.cancelRender();
    void this.pdfDoc?.destroy();
    this.pdfDoc = null;
    this.pdfData = null;
    this.pageCount = 0;
    this.pageNum = 1;
    this.error = '';
    this.modo = 'pdfjs';
    this.iframeUrlSeguro = null;

    if (!this.src) {
      this.loading = false;
      return;
    }

    this.loading = true;
    try {
      const data = await this.leerPdfComoArrayBuffer(this.src);
      if (gen !== this.loadGen) return;

      const head = new Uint8Array(data.slice(0, 5));
      const magic = String.fromCharCode(...head);
      if (!magic.startsWith('%PDF')) {
        this.activarFallbackIframe(gen);
        return;
      }

      this.pdfData = data;
      const task = pdfjsLib.getDocument({ data, useSystemFonts: true });
      const doc = await task.promise;
      if (gen !== this.loadGen) {
        void doc.destroy();
        return;
      }
      this.pdfDoc = doc;
      this.pageCount = doc.numPages;
      this.loading = false;
      this.setupResizeObserver();
      await this.esperarVistaLista();
      if (gen !== this.loadGen) return;
      await this.renderPage();
    } catch {
      if (gen !== this.loadGen) return;
      this.activarFallbackIframe(gen);
    }
  }

  private activarFallbackIframe(gen: number): void {
    if (gen !== this.loadGen || !this.src) return;
    this.modo = 'iframe';
    this.loading = false;
    this.error = '';
    this.iframeUrlSeguro = this.sanitizer.bypassSecurityTrustResourceUrl(
      `${this.src}#toolbar=1&navpanes=0&view=FitH`,
    );
  }

  private async leerPdfComoArrayBuffer(url: string): Promise<ArrayBuffer> {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.arrayBuffer();
  }

  private setupResizeObserver(): void {
    const viewport = this.viewportRef?.nativeElement;
    if (!viewport || this.resizeObserver) return;
    this.resizeObserver = new ResizeObserver(() => {
      if (this.fitWidth && this.modo === 'pdfjs') void this.renderPage();
    });
    this.resizeObserver.observe(viewport);
  }

  private esperarVistaLista(): Promise<void> {
    return new Promise((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
    });
  }

  private cancelRender(): void {
    try {
      this.renderTask?.cancel();
    } catch {
      /* ignore */
    }
    this.renderTask = null;
  }

  private async renderPage(): Promise<void> {
    if (this.modo !== 'pdfjs') return;
    const doc = this.pdfDoc;
    const canvas = this.canvasRef?.nativeElement;
    const viewportEl = this.viewportRef?.nativeElement;
    if (!doc || !canvas || !viewportEl) {
      await this.esperarVistaLista();
      const canvasRetry = this.canvasRef?.nativeElement;
      const viewportRetry = this.viewportRef?.nativeElement;
      if (!doc || !canvasRetry || !viewportRetry) return;
      return this.renderPageEn(canvasRetry, viewportRetry, doc);
    }
    return this.renderPageEn(canvas, viewportEl, doc);
  }

  private async renderPageEn(
    canvas: HTMLCanvasElement,
    viewportEl: HTMLDivElement,
    doc: PDFDocumentProxy,
  ): Promise<void> {
    this.cancelRender();
    const gen = this.loadGen;

    try {
      const page = await doc.getPage(this.pageNum);
      if (gen !== this.loadGen) return;

      const base = page.getViewport({ scale: 1 });
      let scale = this.scale;
      if (this.fitWidth) {
        const pad = 24;
        const available = Math.max(280, viewportEl.clientWidth - pad);
        scale = available / base.width;
        this.scale = scale;
      }

      const viewport = page.getViewport({ scale });
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const outputScale = window.devicePixelRatio || 1;
      canvas.width = Math.floor(viewport.width * outputScale);
      canvas.height = Math.floor(viewport.height * outputScale);
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;
      ctx.setTransform(outputScale, 0, 0, outputScale, 0, 0);

      const task = page.render({ canvasContext: ctx, viewport });
      this.renderTask = task;
      await task.promise;
      if (gen !== this.loadGen) return;
      this.renderTask = null;
    } catch (err: unknown) {
      if (gen !== this.loadGen) return;
      const msg = err instanceof Error ? err.message : '';
      if (msg.includes('cancelled') || msg.includes('canceled')) return;
      this.activarFallbackIframe(gen);
    }
  }
}
