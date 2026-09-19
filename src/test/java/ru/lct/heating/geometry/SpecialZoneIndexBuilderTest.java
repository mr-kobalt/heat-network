package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

class SpecialZoneIndexBuilderTest {

    @Test
    void build_specialRules_createsZonesAndSkipsProhibited() {
        RestrictionRulesProperties properties = rules(
                rule(RestrictionMode.SPECIAL, 1.5, 1.6),
                rule(RestrictionMode.PROHIBITED, 1.0, null));
        SpecialZoneIndexBuilder builder = builder(properties);
        NetworkDataset dataset = dataset(
                restriction("r1", "road"), restriction("r2", "water"));
        SpecialZoneIndex index = builder.build(dataset, 100, new ArrayList<>());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void build_specialRuleWithoutCoefficient_warnsAndSkips() {
        RestrictionRulesProperties properties = rules(rule(RestrictionMode.SPECIAL, 1.5, null));
        SpecialZoneIndexBuilder builder = builder(properties);
        List<String> warnings = new ArrayList<>();
        SpecialZoneIndex index = builder.build(dataset(restriction("r1", "road")), 100, warnings);
        assertThat(index.size()).isZero();
        assertThat(warnings).anyMatch(warning -> warning.contains("SPECIAL_WITHOUT_KS"));
    }

    private SpecialZoneIndexBuilder builder(RestrictionRulesProperties properties) {
        return new SpecialZoneIndexBuilder(new RestrictionRuleResolver(properties),
                new RestrictionAxisBuilder(), new EnvelopeCatalog(new HeatingTablesProperties()));
    }

    private RestrictionRulesProperties rules(RestrictionRule... ruleValues) {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> map = new LinkedHashMap<>();
        map.put("road", ruleValues[0]);
        if (ruleValues.length > 1) {
            map.put("water", ruleValues[1]);
        }
        properties.setRules(map);
        return properties;
    }

    private RestrictionRule rule(RestrictionMode mode, double distance, Double kSpecial) {
        RestrictionRule rule = new RestrictionRule();
        rule.setMode(mode);
        rule.setMinDistanceM(distance);
        rule.setKSpecial(kSpecial);
        return rule;
    }

    private NetworkDataset dataset(RestrictionObject... restrictions) {
        return NetworkDataset.builder().restrictions(List.of(restrictions)).build();
    }

    private RestrictionObject restriction(String id, String type) {
        LineString geometry = ru.lct.heating.domain.GeometrySupport.GEOMETRY_FACTORY
                .createLineString(new Coordinate[]{new Coordinate(0, 0), new Coordinate(100, 0)});
        return RestrictionObject.builder().id(id).restrictionType(type).geometry(geometry).build();
    }
}
