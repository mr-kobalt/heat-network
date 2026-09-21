import type { IControl, Map as MapLibreMap } from 'maplibre-gl';

export interface FitZoomControlOptions {
  /** Подогнать вид под все объекты (input + result). */
  onFit: () => void;
  /** Показывать индикатор текущего зума (debug-режим ?tiles=1). */
  debug?: boolean;
}

/**
 * Кнопки управления масштабом карты как нативный MapLibre-контрол:
 * приблизить, отдалить, показать все объекты, компас (сброс/возврат
 * ориентации); в debug — индикатор зума.
 */
export class FitZoomControl implements IControl {
  private map?: MapLibreMap;
  private container?: HTMLDivElement;
  private zoomLabel?: HTMLDivElement;
  private compassButton?: HTMLButtonElement;
  private compassNeedle?: HTMLSpanElement;
  /** Ориентация пользователя до сброса на север. */
  private lastBearing: number | null = null;

  private readonly syncZoom = () => this.updateZoom();
  private readonly syncCompass = () => this.updateCompass();

  constructor(private readonly options: FitZoomControlOptions) {}

  onAdd(map: MapLibreMap): HTMLElement {
    this.map = map;
    const container = document.createElement('div');
    container.className = 'maplibregl-ctrl maplibregl-ctrl-group';
    container.appendChild(this.button('+', 'Приблизить', () => map.zoomIn()));
    container.appendChild(this.button('\u2212', 'Отдалить', () => map.zoomOut()));
    container.appendChild(this.button('\u2922', 'Показать все объекты', () => this.options.onFit()));
    container.appendChild(this.compass());

    if (this.options.debug) {
      const label = document.createElement('div');
      label.className = 'maplibregl-ctrl-zoom-readout';
      label.style.cssText = 'padding:2px 6px;font:11px/16px monospace;text-align:center;'
        + 'color:#333;background:#fff;border-top:1px solid #ddd;';
      label.textContent = this.zoomText();
      container.appendChild(label);
      this.zoomLabel = label;
      map.on('zoom', this.syncZoom);
      map.on('moveend', this.syncZoom);
    }

    map.on('rotate', this.syncCompass);
    this.updateCompass();

    this.container = container;
    return container;
  }

  onRemove(): void {
    if (this.map) {
      this.map.off('rotate', this.syncCompass);
      if (this.options.debug) {
        this.map.off('zoom', this.syncZoom);
        this.map.off('moveend', this.syncZoom);
      }
    }
    this.container?.remove();
    this.map = undefined;
    this.container = undefined;
    this.zoomLabel = undefined;
    this.compassButton = undefined;
    this.compassNeedle = undefined;
    this.lastBearing = null;
  }

  private button(text: string, title: string, onClick: () => void): HTMLButtonElement {
    const button = document.createElement('button');
    button.type = 'button';
    button.title = title;
    button.setAttribute('aria-label', title);
    button.textContent = text;
    button.style.cssText = 'font-size:16px;line-height:1;';
    button.addEventListener('click', (event) => {
      event.preventDefault();
      onClick();
    });
    return button;
  }

  /** Компас: виден при повороте карты; клик — север, повторный — возврат. */
  private compass(): HTMLButtonElement {
    const button = this.button('', 'Сбросить ориентацию (север сверху)', () => this.toggleBearing());
    button.className = 'maplibregl-ctrl-compass-custom';
    const needle = document.createElement('span');
    needle.className = 'maplibregl-ctrl-compass-needle';
    // Двухцветный ромб: северная половина контрастная, южная — светлая.
    needle.innerHTML = '<svg width="20" height="20" viewBox="0 0 20 20" aria-hidden="true">'
      + '<polygon points="10,2 15,10 5,10" fill="#dc2626" />'
      + '<polygon points="10,18 15,10 5,10" fill="#cbd5e1" />'
      + '<polygon points="10,2 15,10 10,18 5,10" fill="none" stroke="#475569" stroke-width="0.8" />'
      + '</svg>';
    needle.setAttribute('aria-hidden', 'true');
    needle.style.cssText = 'display:flex;align-items:center;justify-content:center;'
      + 'width:100%;height:100%;line-height:1;transform-origin:center;';
    button.textContent = '';
    button.appendChild(needle);
    button.style.display = 'none';
    this.compassNeedle = needle;
    this.compassButton = button;
    return button;
  }

  private toggleBearing(): void {
    const map = this.map;
    if (!map) {
      return;
    }
    const bearing = map.getBearing();
    if (Math.abs(bearing) > 1e-6) {
      this.lastBearing = bearing;
      map.setBearing(0);
    } else if (this.lastBearing !== null) {
      map.setBearing(this.lastBearing);
    }
  }

  private updateCompass(): void {
    const map = this.map;
    const button = this.compassButton;
    if (!map || !button) {
      return;
    }
    const bearing = map.getBearing();
    const rotated = Math.abs(bearing) > 1e-6;
    if (rotated) {
      this.lastBearing = bearing;
    }
    if (this.compassNeedle) {
      this.compassNeedle.style.transform = `rotate(${-bearing}deg)`;
    }
    button.style.display = rotated || this.lastBearing !== null ? '' : 'none';
    button.title = rotated ? 'Сбросить ориентацию (север сверху)' : 'Вернуть ориентацию карты';
    button.setAttribute('aria-label', button.title);
  }

  private updateZoom(): void {
    if (this.zoomLabel) {
      this.zoomLabel.textContent = this.zoomText();
    }
  }

  private zoomText(): string {
    return this.map ? `z${this.map.getZoom().toFixed(1)}` : 'z—';
  }
}
