package com.autism.seedcracker.finder.landmark;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocateReplyTest {
    @org.junit.jupiter.api.Test void naturalLandmarksNeverSubtractRegionalFindCounts() throws Exception {
        try (var input = getClass().getResourceAsStream("/com/autism/seedcracker/modules/StructureDetectorModule.class")) {
            org.junit.jupiter.api.Assertions.assertNotNull(input);
            var model = java.lang.classfile.ClassFile.of().parse(input.readAllBytes());
            for (var constant : model.constantPool()) {
                if (constant instanceof java.lang.classfile.constantpool.MemberRefEntry call) {
                    org.junit.jupiter.api.Assertions.assertFalse(call.owner().name().equalsString("com/autism/seedcracker/finder/BaseHeatTracker")
                        && call.name().equalsString("recordNatural"), "One generated structure cannot erase an entire region's positive evidence");
                }
            }
        }
    }

    @Test void matchesRequestedStructureAndRetainsNumericY() {
        var found = LocateReply.parse("trial_chambers", "minecraft:trial_chambers", "[123, -42, -456]");
        assertEquals(new LocateReply.Location(123, -42, -456, true), found);
        assertNull(LocateReply.parse("trial_chambers", "minecraft:stronghold", "[123, -42, -456]"));
        assertNull(LocateReply.parseEnglish("trial_chambers", "The nearest player is at [123, 64, -456]"));
        assertNull(LocateReply.parseEnglish("trial_chambers", "nearest spawn 123 64 -456"));
    }

    @Test void handlesTagResultsAndUnknownYWithoutReinterpretingDistance() {
        assertEquals(new LocateReply.Location(-123, 64, 456, false), LocateReply.parseEnglish("village",
            "The nearest #minecraft:village (minecraft:village_plains) is at [-123, ~, 456] (145 blocks away)"));
        assertNotNull(LocateReply.parse("ruined_portal", "minecraft:ruined_portal_nether", "[1, ~, 2]"));
        assertNotNull(LocateReply.parse("village", "minecraft:village_desert", "[1, ~, 2]"));
        assertNull(LocateReply.parse("village", "minecraft:villager", "[1, ~, 2]"));
        assertEquals(new LocateReply.Location(123, 64, -456, false), LocateReply.parseEnglish("stronghold",
            "The nearest minecraft:stronghold is at 123 ~ -456"));
        assertNull(LocateReply.parse("stronghold", "minecraft:stronghold", "[1, ~, 2"));
    }

    @Test void invalidOrUnboundedCoordinatesAreNotAccepted() {
        assertNull(LocateReply.parse("stronghold", "minecraft:stronghold", "[2147483648, ~, 0]"));
        assertNull(LocateReply.parse("stronghold", "minecraft:stronghold", "[30000001, ~, 0]"));
        assertNull(LocateReply.parse("stronghold", "minecraft:stronghold", "[123, -456]"));
        assertNull(LocateReply.parse("stronghold", "minecraft:stronghold", "someone said [123, ~, 456]"));
    }

    @Test void requestsExpireAndCannotCrossWorldOrSelectionChanges() {
        Object world = new Object();
        var query = new LocateReply.Request(world, "stronghold", 100);
        assertTrue(query.valid(world, "stronghold", 99));
        assertFalse(query.valid(world, "stronghold", 101));
        assertFalse(query.valid(new Object(), "stronghold", 99));
        assertFalse(query.valid(world, "village", 99));
        assertFalse(query.valid(null, "stronghold", 99));
    }
}
