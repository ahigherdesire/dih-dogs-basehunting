package com.autism.seedcracker.finder;

import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.MemberRefEntry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FinderBlockEntitySafetyTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "kaptainwutax/seedcrackerX/finder/decorator/DungeonFinder",
        "kaptainwutax/seedcrackerX/finder/structure/ShipwreckFinder"
    })
    void workerFindersNeverResolveLiveBlockEntities(String name) throws Exception {
        try (var bytes = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(bytes, name);
            var type = ClassFile.of().parse(bytes.readAllBytes());
            for (var constant : type.constantPool()) {
                if (constant instanceof MemberRefEntry reference)
                    assertNotEquals("getBlockEntity", reference.nameAndType().name().stringValue(),
                        name + " runs on FinderQueue workers; resolving block entities can mutate the render-thread chunk map");
            }
        }
    }
}
