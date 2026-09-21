import { describe, expect, it } from 'vitest';
import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec';
import styleOsm from '../../public/basemap/style.json?raw';
import styleOsmPlaceholder from '../../public/basemap/style-placeholder.json?raw';
import { gridPaperStyle } from './style';
import {
  DIAMETER_COLOR,
  DIAMETER_WIDTH,
  EXISTING_DIAMETER_COLOR,
  EXISTING_DIAMETER_WIDTH,
  PIPE_WIDTH,
  PIPE_WIDTH_HOVER,
  connectionPointColor,
  hoverRadius,
  hoverWidth,
  linePaint,
  restrictionFillColor,
} from './paint';

function styleWith(paint: Record<string, unknown>, filter?: unknown, type = 'line') {
  return {
    version: 8,
    sources: {
      net: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
    },
    layers: [
      {
        id: 'net',
        type,
        source: 'net',
        ...(filter !== undefined ? { filter } : {}),
        paint,
      },
    ],
  };
}

describe('валидация слоёв новой сети по style-spec MapLibre', () => {
  it('базовый слой (viridis + реальная ширина) валиден', () => {
    const errors = validateStyleMin(
      styleWith(linePaint(DIAMETER_COLOR, PIPE_WIDTH), [
        '!=',
        ['get', 'laying_method'],
        'special',
      ]) as never,
    );
    expect(errors).toEqual([]);
  });

  it('пропорциональная ширина (режим по умолчанию) валидна', () => {
    expect(
      validateStyleMin(styleWith(linePaint(DIAMETER_COLOR, hoverWidth(DIAMETER_WIDTH))) as never),
    ).toEqual([]);
    expect(
      validateStyleMin(styleWith(linePaint(EXISTING_DIAMETER_COLOR, hoverWidth(EXISTING_DIAMETER_WIDTH))) as never),
    ).toEqual([]);
  });

  it('слой спецпрохода (пунктир в цвете viridis) валиден', () => {
    const errors = validateStyleMin(
      styleWith(linePaint(DIAMETER_COLOR, PIPE_WIDTH, [3, 2]), ['==', ['get', 'laying_method'], 'special']) as never,
    );
    expect(errors).toEqual([]);
  });

  it('двойная обёртка выражения (прежняя ошибка) невалидна', () => {
    const errors = validateStyleMin(
      styleWith({ 'line-color': [DIAMETER_COLOR], 'line-width': 3 }) as never,
    );
    expect(errors.length).toBeGreaterThan(0);
  });

  it('существующая сеть (grayscale-viridis по Ду) валидна', () => {
    const errors = validateStyleMin(
      styleWith(linePaint(EXISTING_DIAMETER_COLOR, PIPE_WIDTH)) as never,
    );
    expect(errors).toEqual([]);
  });

  it('hover-акценты (feature-state) валидны', () => {
    expect(
      validateStyleMin(
        styleWith(linePaint(DIAMETER_COLOR, PIPE_WIDTH_HOVER), undefined, 'line') as never,
      ),
    ).toEqual([]);
    expect(
      validateStyleMin(
        styleWith({ 'circle-radius': hoverRadius(6), 'circle-color': '#0284c7' }, undefined, 'circle') as never,
      ),
    ).toEqual([]);
  });

  it('заливка ограничений и точки подключения валидны', () => {
    expect(
      validateStyleMin(
        styleWith({ 'fill-color': restrictionFillColor(), 'fill-opacity': 0.25 }, undefined, 'fill') as never,
      ),
    ).toEqual([]);
    expect(
      validateStyleMin(
        styleWith({ 'circle-color': connectionPointColor(), 'circle-radius': 6 }, undefined, 'circle') as never,
      ),
    ).toEqual([]);
  });

  it.each([
    ['style.json', styleOsm],
    ['style-placeholder.json', styleOsmPlaceholder],
  ])('сгенерированный стиль %s валиден', (_file, raw) => {
    expect(validateStyleMin(JSON.parse(raw))).toEqual([]);
  });

  it('стиль миллиметровки валиден', () => {
    expect(validateStyleMin(gridPaperStyle() as never)).toEqual([]);
  });

  it('symbol-слой камер (icon-image + постоянный icon-size) валиден', () => {
    const style = {
      version: 8,
      sources: { chambers: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } } },
      layers: [
        {
          id: 'chambers',
          type: 'symbol',
          source: 'chambers',
          layout: {
            'icon-image': 'chamber-new',
            'icon-size': 0.7,
            'icon-allow-overlap': true,
            'icon-ignore-placement': true,
            'icon-anchor': 'center',
          },
        },
      ],
    };
    expect(validateStyleMin(style as never)).toEqual([]);
  });

  it('feature-state в layout (icon-size) недопустим', () => {
    const style = {
      version: 8,
      sources: { chambers: { type: 'geojson', data: { type: 'FeatureCollection', features: [] }, generateId: true } },
      layers: [
        {
          id: 'chambers',
          type: 'symbol',
          source: 'chambers',
          layout: {
            'icon-image': 'chamber-new',
            'icon-size': ['case', ['boolean', ['feature-state', 'hover'], false], 0.9, 0.7],
          },
        },
      ],
    };
    expect(validateStyleMin(style as never).length).toBeGreaterThan(0);
  });

  it('штриховая заливка зон (fill-pattern) валидна', () => {
    expect(
      validateStyleMin(
        styleWith(
          { 'fill-pattern': 'restriction-zone-pattern', 'fill-opacity': 0.9 },
          undefined,
          'fill',
        ) as never,
      ),
    ).toEqual([]);
  });
});
