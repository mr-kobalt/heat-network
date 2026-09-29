import { describe, expect, it } from 'vitest';
import { formatHash, parseHash } from './useHashRoute';

describe('parseHash', () => {
  it('defaults to the map view', () => {
    expect(parseHash('')).toEqual({ view: 'map', docPath: null });
    expect(parseHash('#map')).toEqual({ view: 'map', docPath: null });
  });

  it('parses the documentation view and path', () => {
    expect(parseHash('#docs')).toEqual({ view: 'docs', docPath: null });
    expect(parseHash('#docs/01-project/charter.md'))
      .toEqual({ view: 'docs', docPath: '01-project/charter.md' });
  });

  it('parses the api view', () => {
    expect(parseHash('#api')).toEqual({ view: 'api', docPath: null });
  });
});

describe('formatHash', () => {
  it('round-trips routes', () => {
    const routes = [
      { view: 'map' as const, docPath: null },
      { view: 'docs' as const, docPath: null },
      { view: 'docs' as const, docPath: '03-architecture/adr/README.md' },
      { view: 'api' as const, docPath: null },
    ];
    routes.forEach((route) => {
      expect(parseHash(formatHash(route))).toEqual(route);
    });
  });
});
