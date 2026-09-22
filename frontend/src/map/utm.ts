/**
 * Прямое и обратное преобразование WGS84 ⇄ UTM зоны 37N (EPSG:32637) —
 * минимальная реализация Transverse Mercator для построения контуров ячеек
 * сетки на клиенте (ADR-0037). Формулы — стандартные (Snyder).
 */

const A = 6378137.0;
const F = 1 / 298.257223563;
const E2 = F * (2 - F);
const K0 = 0.9996;
const FALSE_EASTING = 500000.0;
const CENTRAL_MERIDIAN_DEG = 39.0;

export interface UtmPoint {
  x: number;
  y: number;
}

/** WGS84 (градусы) → UTM 37N (метры). */
export function wgs84ToUtm(lonDeg: number, latDeg: number): UtmPoint {
  const phi = (latDeg * Math.PI) / 180;
  const lambda = (lonDeg * Math.PI) / 180;
  const lambda0 = (CENTRAL_MERIDIAN_DEG * Math.PI) / 180;
  const ep2 = E2 / (1 - E2);

  const sinPhi = Math.sin(phi);
  const cosPhi = Math.cos(phi);
  const tanPhi = Math.tan(phi);

  const n = A / Math.sqrt(1 - E2 * sinPhi * sinPhi);
  const t = tanPhi * tanPhi;
  const c = ep2 * cosPhi * cosPhi;
  const a = (lambda - lambda0) * cosPhi;
  const m = A * (
    (1 - E2 / 4 - (3 * E2 * E2) / 64 - (5 * E2 * E2 * E2) / 256) * phi
    - ((3 * E2) / 8 + (3 * E2 * E2) / 32 + (45 * E2 * E2 * E2) / 1024) * Math.sin(2 * phi)
    + ((15 * E2 * E2) / 256 + (45 * E2 * E2 * E2) / 1024) * Math.sin(4 * phi)
    - ((35 * E2 * E2 * E2) / 3072) * Math.sin(6 * phi)
  );

  const a2 = a * a;
  const x = K0 * n * (
    a + ((1 - t + c) * a * a2) / 6
    + ((5 - 18 * t + t * t + 72 * c - 58 * ep2) * a * a2 * a2) / 120
  ) + FALSE_EASTING;
  const y = K0 * (
    m + n * tanPhi * (
      a2 / 2
      + ((5 - t + 9 * c + 4 * c * c) * a2 * a2) / 24
      + ((61 - 58 * t + t * t + 600 * c - 330 * ep2) * a2 * a2 * a2) / 720
    )
  );
  return { x, y };
}

/** UTM 37N (метры) → WGS84 (градусы). */
export function utmToWgs84(x: number, y: number): [number, number] {
  const ep2 = E2 / (1 - E2);
  const e1 = (1 - Math.sqrt(1 - E2)) / (1 + Math.sqrt(1 - E2));
  const m = y / K0;
  const mu = m / (A * (1 - E2 / 4 - (3 * E2 * E2) / 64 - (5 * E2 * E2 * E2) / 256));

  const phi1 = mu
    + ((3 * e1) / 2 - (27 * e1 * e1 * e1) / 32) * Math.sin(2 * mu)
    + ((21 * e1 * e1) / 16 - (55 * e1 * e1 * e1 * e1) / 32) * Math.sin(4 * mu)
    + ((151 * e1 * e1 * e1) / 96) * Math.sin(6 * mu)
    + ((1097 * e1 * e1 * e1 * e1) / 512) * Math.sin(8 * mu);

  const sinPhi1 = Math.sin(phi1);
  const cosPhi1 = Math.cos(phi1);
  const tanPhi1 = Math.tan(phi1);
  const n1 = A / Math.sqrt(1 - E2 * sinPhi1 * sinPhi1);
  const t1 = tanPhi1 * tanPhi1;
  const c1 = ep2 * cosPhi1 * cosPhi1;
  const r1 = (A * (1 - E2)) / Math.pow(1 - E2 * sinPhi1 * sinPhi1, 1.5);
  const d = (x - FALSE_EASTING) / (n1 * K0);

  const d2 = d * d;
  const phi = phi1 - ((n1 * tanPhi1) / r1) * (
    d2 / 2
    - ((5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * ep2) * d2 * d2) / 24
    + ((61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * ep2 - 3 * c1 * c1) * d2 * d2 * d2) / 720
  );
  const lambda = ((CENTRAL_MERIDIAN_DEG * Math.PI) / 180) + (
    d
    - ((1 + 2 * t1 + c1) * d2 * d) / 6
    + ((5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * ep2 + 24 * t1 * t1) * d2 * d2 * d) / 120
  ) / cosPhi1;

  return [(lambda * 180) / Math.PI, (phi * 180) / Math.PI];
}
