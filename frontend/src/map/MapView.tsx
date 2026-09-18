import { useEffect, useRef, useState } from 'react';
import maplibregl from 'maplibre-gl';
import 'maplibre-gl/dist/maplibre-gl.css';
import { Protocol } from 'pmtiles';
import { blankStyle, basemapStyleUrl } from './style';
import { OVERLAY_LAYER_IDS, fitToData, updateOverlays } from './layers';
import { useStore } from '../store';
import type { GeoFeature } from '../types';

export function MapView() {
  const containerRef = useRef<HTMLDivElement>(null);
  const mapRef = useRef<maplibregl.Map | null>(null);
  const [ready, setReady] = useState(false);

  const input = useStore((state) => state.input);
  const result = useStore((state) => state.result);
  const activeVariant = useStore((state) => state.activeVariant);
  const visibility = useStore((state) => state.visibility);
  const select = useStore((state) => state.select);

  useEffect(() => {
    const container = containerRef.current;
    if (!container) {
      return undefined;
    }
    const protocol = new Protocol();
    maplibregl.addProtocol('pmtiles', protocol.tile);
    const map = new maplibregl.Map({
      container,
      style: basemapStyleUrl() ?? blankStyle(),
      center: [37.634, 55.7],
      zoom: 13,
      attributionControl: false,
    });
    mapRef.current = map;
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 120, unit: 'metric' }), 'bottom-left');
    map.on('load', () => setReady(true));
    map.on('click', (event) => {
      const layers = OVERLAY_LAYER_IDS.filter((id) => Boolean(map.getLayer(id)));
      const found = map.queryRenderedFeatures(event.point, { layers });
      if (found.length > 0) {
        select(found[0] as unknown as GeoFeature);
      }
    });
    return () => {
      map.remove();
      maplibregl.removeProtocol('pmtiles');
      mapRef.current = null;
      setReady(false);
    };
  }, [select]);

  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) {
      return;
    }
    updateOverlays(map, { input, result, activeVariant, visibility });
  }, [ready, input, result, activeVariant, visibility]);

  useEffect(() => {
    const map = mapRef.current;
    if (!ready || !map) {
      return;
    }
    fitToData(map, { input, result, activeVariant, visibility });
    // Переподгонка вида — только при смене данных/варианта, не при тумблерах слоёв.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, input, result, activeVariant]);

  return <div ref={containerRef} style={{ position: 'absolute', inset: 0 }} />;
}
