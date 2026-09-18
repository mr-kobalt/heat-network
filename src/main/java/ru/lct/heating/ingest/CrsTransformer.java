package ru.lct.heating.ingest;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateFilter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.proj4j.BasicCoordinateTransform;
import org.locationtech.proj4j.CRSFactory;
import org.locationtech.proj4j.CoordinateReferenceSystem;
import org.locationtech.proj4j.CoordinateTransform;
import org.locationtech.proj4j.ProjCoordinate;
import org.springframework.stereotype.Component;

/**
 * Преобразование координат EPSG:4326 &lt;-&gt; EPSG:32637 (ADR-0014).
 * Параметры UTM-зоны заданы явно, без внешнего набора EPSG (офлайн).
 */
@Component
public class CrsTransformer {

    public static final int EPSG_WGS84 = 4326;
    public static final int EPSG_UTM_37N = 32637;

    private static final String WGS84 = "+proj=longlat +datum=WGS84 +no_defs";
    private static final String UTM_37N = "+proj=utm +zone=37 +datum=WGS84 +units=m +no_defs";

    private final CoordinateTransform toUtm;
    private final CoordinateTransform toWgs84;

    public CrsTransformer() {
        CRSFactory factory = new CRSFactory();
        CoordinateReferenceSystem wgs84 = factory.createFromParameters("WGS84", WGS84);
        CoordinateReferenceSystem utm37 = factory.createFromParameters("UTM37N", UTM_37N);
        this.toUtm = new BasicCoordinateTransform(wgs84, utm37);
        this.toWgs84 = new BasicCoordinateTransform(utm37, wgs84);
    }

    public Geometry toUtm(Geometry geometry) {
        return transform(geometry, toUtm, EPSG_UTM_37N);
    }

    public Geometry toWgs84(Geometry geometry) {
        return transform(geometry, toWgs84, EPSG_WGS84);
    }

    private Geometry transform(Geometry geometry, CoordinateTransform transform, int srid) {
        if (geometry == null) {
            return null;
        }
        Geometry copy = geometry.copy();
        copy.apply((CoordinateFilter) coordinate -> {
            ProjCoordinate source = new ProjCoordinate(coordinate.x, coordinate.y);
            ProjCoordinate target = transform.transform(source, new ProjCoordinate());
            coordinate.x = target.x;
            coordinate.y = target.y;
        });
        copy.geometryChanged();
        copy.setSRID(srid);
        return copy;
    }

    public Coordinate toUtm(Coordinate coordinate) {
        ProjCoordinate target = toUtm.transform(
                new ProjCoordinate(coordinate.x, coordinate.y), new ProjCoordinate());
        return new Coordinate(target.x, target.y);
    }
}
