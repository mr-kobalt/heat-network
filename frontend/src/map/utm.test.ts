import { describe, expect, it } from 'vitest';
import { utmToWgs84, wgs84ToUtm } from './utm';

describe('utm (зона 37N)', () => {
  it('round-trips a point near Moscow', () => {
    const lon = 37.632012226474316;
    const lat = 55.700048261255056;

    const { x, y } = wgs84ToUtm(lon, lat);
    const [lonBack, latBack] = utmToWgs84(x, y);

    expect(lonBack).toBeCloseTo(lon, 6);
    expect(latBack).toBeCloseTo(lat, 6);
  });

  it('places Moscow west of the zone central meridian', () => {
    // Лоскут Москвы (lon 37.6°) лежит западнее осевого меридиана зоны 37 (39°).
    const { x, y } = wgs84ToUtm(37.6, 55.7);
    expect(x).toBeGreaterThan(300000);
    expect(x).toBeLessThan(500000);
    expect(y).toBeGreaterThan(6000000);
    expect(y).toBeLessThan(6400000);
  });
});
