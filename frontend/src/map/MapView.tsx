import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { Protocol } from 'pmtiles';
import type { BasemapId } from './style';
import { resolveBasemapStyle } from './style';
import {
  CHAMBER_EXISTING_COLOR,
  CHAMBER_NEW_COLOR,
  SELECTABLE_LAYER_IDS,
  fitToData,
  updateOverlays,
} from './layers';
import { addOverlayIcons, overlayIconById } from './icons';
import { EMPTY_COLLECTION, buildRestrictionBuffers } from './buffers';
import { FitZoomControl } from './controls';
import { DetailsPanel } from '../components/DetailsPanel';
import { useStore } from '../store';
import type { GeoFeature } from '../types';

let pmtilesProtocolRegistered = false;

/**
 * Регистрируем протокол pmtiles один раз на всё приложение. Повторная
 * регистрация и снятие при ремаунте (React StrictMode в dev) приводили к тому,
 * что часть запросов тайлов «повисала» пустыми.
 */
function ensurePmtilesProtocol(): void {
  if (!pmtilesProtocolRegistered) {
    maplibregl.addProtocol('pmtiles', new Protocol().tile);
    pmtilesProtocolRegistered = true;
  }
}

/** Карта занимает видимую область Main (без header/navbar/aside/footer). */
const CONTAINER_STYLE = {
  position: 'absolute',
  top: 'calc(var(--app-shell-header-offset, 0px) + var(--app-shell-padding, 0px))',
  left: 'calc(var(--app-shell-navbar-offset, 0px) + var(--app-shell-padding, 0px))',
  right: 'calc(var(--app-shell-aside-offset, 0px) + var(--app-shell-padding, 0px))',
  bottom: 'calc(var(--app-shell-footer-offset, 0px) + var(--app-shell-padding, 0px))',
} as const;

export function MapView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const basemapRef = useRef<BasemapId>('osm');
  const appliedBasemapRef = useRef<BasemapId | null>(null);
  const [ready, setReady] = useState(false);
  const [popupContainer, setPopupContainer] = useState<HTMLDivElement | null>(null);

  const input = useStore((state) => state.input);
  const result = useStore((state) => state.result);
  const activeVariant = useStore((state) => state.activeVariant);
  const visibility = useStore((state) => state.visibility);
  const basemap = useStore((state) => state.basemap);
  const realPipeScale = useStore((state) => state.realPipeScale);
  const selected = useStore((state) => state.selected);
  const selectedAnchor = useStore((state) => state.selectedAnchor);
  const select = useStore((state) => state.select);

  const buffers = useMemo(
    () => (visibility.restrictionBuffers ? buildRestrictionBuffers(input) : EMPTY_COLLECTION),
    [visibility.restrictionBuffers, input],
  );

  const overlayData = useMemo(
    () => ({ input, result, activeVariant, visibility, buffers, realPipeScale }),
    [input, result, activeVariant, visibility, buffers, realPipeScale],
  );
  const overlayRef = useRef(overlayData);
  useEffect(() => {
    overlayRef.current = overlayData;
  }, [overlayData]);

  const debugTiles = useMemo(
    () => new URLSearchParams(window.location.search).get('tiles') === '1',
    [],
  );

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return undefined;
    }
    let cancelled = false;
    ensurePmtilesProtocol();

    const init = async () => {
      const initialBasemap = basemapRef.current;
      const style = await resolveBasemapStyle(initialBasemap);
      if (cancelled) {
        return;
      }
      const map = new maplibregl.Map({
        container,
        style,
        center: [37.634, 55.7],
        zoom: 13,
        attributionControl: false,
      });
      mapRef.current = map;
      appliedBasemapRef.current = initialBasemap;
      map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-right');
      map.addControl(new maplibregl.ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-left');
      map.addControl(
        new FitZoomControl({
          debug: debugTiles,
          onFit: () => {
            const current = mapRef.current;
            if (current) {
              fitToData(current, overlayRef.current);
            }
          },
        }),
        'top-right',
      );
      if (debugTiles) {
        map.showTileBoundaries = true;
        map.on('error', (event) => {
          const error = (event as { error?: { message?: string; url?: string } }).error;
          console.warn('[map] error:', error?.message ?? event, error?.url ?? '');
        });
      }
      // Иконки и оверлеи добавляются после каждой загрузки стиля (в т.ч. смены
      // подложки через setStyle, когда слои стиля пересоздаются).
      map.on('style.load', () => {
        addOverlayIcons(map, CHAMBER_EXISTING_COLOR, CHAMBER_NEW_COLOR);
        updateOverlays(map, overlayRef.current);
      });
      map.on('load', () => setReady(true));
      map.on('styleimagemissing', (event) => {
        if (map.hasImage(event.id)) {
          return;
        }
        // Для наших изображений (камеры, зоны, миллиметровка) добавляем
        // настоящую картинку, иначе — прозрачную заглушку 1×1.
        const icon = overlayIconById(event.id, CHAMBER_EXISTING_COLOR, CHAMBER_NEW_COLOR);
        map.addImage(
          event.id,
          (icon ?? { width: 1, height: 1, data: new Uint8Array(4) }) as never,
        );
      });
      map.on('click', (event) => {
        const layers = SELECTABLE_LAYER_IDS.filter((id) => Boolean(map.getLayer(id)));
        const found = map.queryRenderedFeatures(event.point, { layers });
        if (found.length > 0) {
          select(found[0] as unknown as GeoFeature, [event.lngLat.lng, event.lngLat.lat]);
        } else {
          select(null);
        }
      });

      // Подсветка объектов, доступных для клика: курсор + акцент через feature-state.
      let hovered: { source: string; id: string | number } | null = null;
      const clearHover = () => {
        if (hovered) {
          map.setFeatureState({ source: hovered.source, id: hovered.id }, { hover: false });
          hovered = null;
        }
        map.getCanvas().style.cursor = '';
      };
      map.on('mousemove', (event) => {
        const layers = SELECTABLE_LAYER_IDS.filter((id) => Boolean(map.getLayer(id)));
        const feature = layers.length > 0
          ? map.queryRenderedFeatures(event.point, { layers })[0]
          : undefined;
        if (!feature || feature.source === undefined || feature.id === undefined) {
          clearHover();
          return;
        }
        if (hovered && (hovered.source !== feature.source || hovered.id !== feature.id)) {
          map.setFeatureState({ source: hovered.source, id: hovered.id }, { hover: false });
          hovered = null;
        }
        hovered = { source: feature.source, id: feature.id };
        map.setFeatureState(hovered, { hover: true });
        map.getCanvas().style.cursor = 'pointer';
      });
      map.on('mouseleave', clearHover);
    };
    void init();

    return () => {
      cancelled = true;
      mapRef.current?.remove();
      mapRef.current = null;
      setReady(false);
    };
  }, [select, debugTiles]);

  // Пересчёт размеров канвы при изменении видимой области (тумблеры панелей, окно).
  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return undefined;
    }
    const observer = new ResizeObserver(() => {
      mapRef.current?.resize();
    });
    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) {
      return;
    }
    updateOverlays(map, overlayData);
  }, [ready, overlayData]);

  // Смена подложки: setStyle пересоздаёт слои; оверлеи вернёт style.load.
  useEffect(() => {
    basemapRef.current = basemap;
    const map = mapRef.current;
    if (!ready || !map || appliedBasemapRef.current === basemap) {
      return undefined;
    }
    appliedBasemapRef.current = basemap;
    let cancelled = false;
    void resolveBasemapStyle(basemap).then((style) => {
      if (cancelled || !mapRef.current) {
        return;
      }
      mapRef.current.setStyle(style, { diff: false });
    });
    return () => {
      cancelled = true;
    };
  }, [ready, basemap]);

  // Попап с параметрами выбранного объекта (вместо правой панели).
  // Контент рендерится порталом в основном React-дереве — иначе попап пустой.
  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map || !selected || !selectedAnchor) {
      return undefined;
    }
    const container = document.createElement('div');
    const popup = new maplibregl.Popup({
      closeButton: false,
      maxWidth: '320px',
      offset: 10,
      closeOnClick: false,
    })
      .setLngLat(selectedAnchor)
      .setDOMContent(container)
      .addTo(map);
    setPopupContainer(container);
    return () => {
      popup.remove();
    };
  }, [ready, selected, selectedAnchor]);

  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) {
      return;
    }
    fitToData(map, overlayData);
    // Переподгонка вида — только при смене данных/варианта, не при тумблерах слоёв.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, input, result, activeVariant]);

  return (
    <div ref={containerRef} style={CONTAINER_STYLE}>
      {popupContainer && selected
        ? createPortal(
            <DetailsPanel compact feature={selected} onClose={() => select(null)} />,
            popupContainer,
          )
        : null}
    </div>
  );
}
