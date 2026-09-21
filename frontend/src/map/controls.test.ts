import { describe, expect, it, vi } from 'vitest';
import { FitZoomControl } from './controls';

function fakeMap(zoom = 10, bearing = 0) {
  const handlers: Record<string, (() => void)[]> = {};
  let currentZoom = zoom;
  let currentBearing = bearing;
  return {
    zoomIn: vi.fn(),
    zoomOut: vi.fn(),
    getZoom: () => currentZoom,
    setZoom: (value: number) => {
      currentZoom = value;
    },
    getBearing: () => currentBearing,
    setBearing: (value: number) => {
      currentBearing = value;
      (handlers.rotate ?? []).forEach((handler) => handler());
    },
    on: vi.fn((event: string, handler: () => void) => {
      (handlers[event] ??= []).push(handler);
    }),
    off: vi.fn(),
    emit: (event: string) => (handlers[event] ?? []).forEach((handler) => handler()),
  };
}

describe('FitZoomControl', () => {
  it('renders zoom buttons and wires actions', () => {
    const onFit = vi.fn();
    const map = fakeMap();
    const control = new FitZoomControl({ onFit });
    const element = control.onAdd(map as never);

    const buttons = element.querySelectorAll('button');
    expect(buttons).toHaveLength(4);

    (buttons[0] as HTMLButtonElement).click();
    (buttons[1] as HTMLButtonElement).click();
    (buttons[2] as HTMLButtonElement).click();

    expect(map.zoomIn).toHaveBeenCalledTimes(1);
    expect(map.zoomOut).toHaveBeenCalledTimes(1);
    expect(onFit).toHaveBeenCalledTimes(1);
    expect(element.querySelector('.maplibregl-ctrl-zoom-readout')).toBeNull();
  });

  it('shows compass only when rotated and toggles bearing', () => {
    const map = fakeMap(10, 0);
    const control = new FitZoomControl({ onFit: () => undefined });
    const element = control.onAdd(map as never);
    const compass = element.querySelector('.maplibregl-ctrl-compass-custom') as HTMLButtonElement;

    expect(compass.style.display).toBe('none');

    map.setBearing(45);
    expect(compass.style.display).not.toBe('none');

    compass.click();
    expect(map.getBearing()).toBe(0);
    expect(compass.style.display).not.toBe('none');

    compass.click();
    expect(map.getBearing()).toBe(45);

    const needle = compass.querySelector('.maplibregl-ctrl-compass-needle') as HTMLSpanElement;
    expect(needle.style.transform).toBe('rotate(-45deg)');
    expect(needle.querySelectorAll('svg polygon').length).toBeGreaterThan(0);
  });

  it('shows zoom readout only in debug mode', () => {
    const map = fakeMap(12.34);
    const control = new FitZoomControl({ onFit: () => undefined, debug: true });
    const element = control.onAdd(map as never);

    const readout = element.querySelector('.maplibregl-ctrl-zoom-readout');
    expect(readout?.textContent).toBe('z12.3');

    map.setZoom(15.06);
    map.emit('zoom');
    expect(readout?.textContent).toBe('z15.1');
  });
});
