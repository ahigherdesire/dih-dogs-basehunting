package com.autism.seedcracker.compat;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Reads actual SDK/MC bytecode without initializing the host, starting Minecraft or applying mixins. */
class DihPortContractTest {
    private final Map<String, ClassNode> types = new HashMap<>();
    private final List<String> errors = new ArrayList<>();

    private ClassNode type(String name) throws IOException {
        if (types.containsKey(name)) return types.get(name);
        try (var input = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            if (input == null) { types.put(name, null); return null; }
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            types.put(name, node);
            return node;
        }
    }

    private boolean member(String owner, String name, String descriptor, boolean field, Set<String> visited) throws IOException {
        if (!visited.add(owner)) return false;
        ClassNode node = type(owner);
        if (node == null) return false;
        if (field ? node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(descriptor))
                  : node.methods.stream().anyMatch(m -> m.name.equals(name) && m.desc.equals(descriptor))) return true;
        if ("<init>".equals(name)) return false;
        if (node.superName != null && member(node.superName, name, descriptor, field, visited)) return true;
        for (String parent : node.interfaces) if (member(parent, name, descriptor, field, visited)) return true;
        return false;
    }

    private static boolean host(String owner) {
        return owner.startsWith("dihclient/") || owner.equals("net/minecraft/client/renderer/rendertype/DihRenderTypes");
    }

    private void reference(String owner, String name, String descriptor, boolean field) throws IOException {
        assertFalse(owner.startsWith("autismclient/"), "AUTISM host reference: " + owner);
        assertFalse(descriptor.contains("Lautismclient/"), "AUTISM descriptor: " + descriptor);
        if (host(owner) && !member(owner, name, descriptor, field, new HashSet<>()))
            errors.add(owner + "." + name + descriptor);
    }

    @Test void nativeDihMemberReferencesResolveAgainstTheSelectedSdk() throws Exception {
        var resource = getClass().getClassLoader().getResource("com/autism/seedcracker/SeedcrackerAddon.class");
        assertNotNull(resource);
        Path root = Path.of(resource.toURI());
        for (int i = 0; i < 4; i++) root = root.getParent();
        try (var paths = Files.walk(root)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                var node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                assertFalse(node.superName != null && node.superName.startsWith("autismclient/"), node.name);
                for (MethodNode method : node.methods) {
                    assertFalse(method.desc.contains("Lautismclient/"), node.name + "." + method.name);
                    for (var instruction : method.instructions) {
                        if (instruction instanceof MethodInsnNode call) reference(call.owner, call.name, call.desc, false);
                        if (instruction instanceof FieldInsnNode field) reference(field.owner, field.name, field.desc, true);
                        if (instruction instanceof InvokeDynamicInsnNode dynamic)
                            for (Object argument : dynamic.bsmArgs) if (argument instanceof Handle handle)
                                reference(handle.getOwner(), handle.getName(), handle.getDesc(), handle.getTag() <= Opcodes.H_PUTSTATIC);
                    }
                }
            }
        }
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) for (int i = 0; i < annotation.values.size(); i += 2)
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        return null;
    }

    private boolean selector(ClassNode target, String selector) throws IOException {
        if (target == null) return false;
        int descriptor = selector.indexOf('(');
        String name = descriptor < 0 ? selector : selector.substring(0, descriptor);
        if (descriptor < 0) return target.methods.stream().anyMatch(m -> m.name.equals(name));
        return member(target.name, name, selector.substring(descriptor), false, new HashSet<>());
    }

    @Test void registeredMixinSelectorsAndShadowFieldsExistOnRealTargets() throws Exception {
        var json = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources", "seedcracker.mixins.json"))).getAsJsonObject();
        String pkg = json.get("package").getAsString().replace('.', '/');
        for (String group : List.of("mixins", "client")) for (var entry : json.getAsJsonArray(group)) {
            String mixinName = pkg + "/" + entry.getAsString();
            ClassNode mixin = type(mixinName);
            assertNotNull(mixin, mixinName);
            var targetNames = new ArrayList<String>();
            for (var annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations))
                if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    if (value(annotation, "value") instanceof List<?> targets)
                        for (Object target : targets) targetNames.add(((Type) target).getInternalName());
                    if (value(annotation, "targets") instanceof List<?> targets)
                        for (Object target : targets) targetNames.add(target.toString().replace('.', '/'));
                }
            assertFalse(targetNames.isEmpty(), mixinName);
            for (String targetName : targetNames) {
                ClassNode target = type(targetName);
                assertNotNull(target, "Missing real mixin target " + targetName);
                for (MethodNode method : mixin.methods)
                    for (var annotation : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                        if (value(annotation, "method") instanceof List<?> selectors)
                            for (Object selected : selectors) assertTrue(selector(target, selected.toString()), mixinName + " -> " + targetName + "." + selected);
                        if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))
                            assertTrue(member(targetName, method.name, method.desc, false, new HashSet<>()), mixinName + " shadow " + method.name);
                        if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/gen/Accessor;")) {
                            String field = (String) value(annotation, "value");
                            assertNotNull(field, "Accessor must identify its target field: " + mixinName);
                            Type signature = Type.getMethodType(method.desc);
                            String descriptor = signature.getReturnType().equals(Type.VOID_TYPE)
                                ? signature.getArgumentTypes()[0].getDescriptor() : signature.getReturnType().getDescriptor();
                            assertTrue(member(targetName, field, descriptor, true, new HashSet<>()), mixinName + " accessor " + field);
                        }
                    }
                for (FieldNode field : mixin.fields)
                    for (var annotation : annotations(field.visibleAnnotations, field.invisibleAnnotations))
                        if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))
                            assertTrue(member(targetName, field.name, field.desc, true, new HashSet<>()), mixinName + " shadow " + field.name);
            }
        }
    }

    @Test void legacyWorldgenIsPresentOnlyOnItsSupportedTarget() throws IOException {
        ClassNode scanner = type("com/autism/seedcracker/spectre/chunks/seedscan/AdvancedScanner");
        ClassNode finder = type("com/autism/seedcracker/modules/ChunkFinderModule");
        assertNotNull(type("com/autism/seedcracker/spectre/chunks/seedscan/GeodePredictor"), "Independent native geodes exist on both targets");
        assertEquals("26.3".equals(System.getProperty("dogs.minecraft")),
            type("com/autism/seedcracker/spectre/chunks/seedscan/NativeGeodeWorld263") != null);
        for (String helper : List.of("com/autism/seedcracker/bedrock/SeedSeedProvider", "com/autism/seedcracker/spectre/palette/GeodeSeedSelection"))
            for (MethodNode method : type(helper).methods)
                for (var instruction : method.instructions)
                    if (instruction instanceof MethodInsnNode call)
                        assertFalse(call.owner.endsWith("/AdvancedScanner"), "Seed fingerprints cannot depend on the unavailable legacy backend");
        if ("26.3".equals(System.getProperty("dogs.minecraft"))) {
            assertNull(scanner, "Do not ship a legacy worldgen implementation or API substitute on 26.3");
            assertFalse(finder.methods.stream().anyMatch(m -> m.name.equals("tickAdvanced")));
            assertTrue(finder.methods.stream().flatMap(m -> java.util.Arrays.stream(m.instructions.toArray()))
                .anyMatch(i -> i instanceof LdcInsnNode literal && literal.cst instanceof String message
                    && message.contains("Seed-aware unavailable on 26.3")));
        } else {
            assertNotNull(scanner);
            assertTrue(finder.methods.stream().anyMatch(m -> m.name.equals("tickAdvanced")));
        }
    }

    @Test void nativeIdentityExclusionsAndNullableSyncBoundaryRemainExplicit() throws IOException {
        var metadata = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources", "fabric.mod.json"))).getAsJsonObject();
        assertEquals("dogs-basehunting", metadata.get("id").getAsString());
        assertTrue(metadata.getAsJsonObject("entrypoints").has("dih"));
        assertFalse(metadata.getAsJsonObject("entrypoints").has("autism"));
        assertEquals("GPL-3.0-only", metadata.get("license").getAsString());
        Path source = Path.of("src", "main", "java", "com", "autism", "seedcracker");
        String addon = Files.readString(source.resolve("SeedcrackerAddon.java"));
        for (String excluded : List.of("SeedRay", "Sprint", "FreeLook", "AutoTool", "NameProtect", "ScoreboardHider", "FastPlace", "FakeLatency", "SwingSpeed", "TPASpammer", "FakePay", "FakePayments", "FakeRoles", "FakeStats", "SkinChanger"))
            assertFalse(addon.contains("new " + excluded + "Module(") || addon.contains("." + excluded + "Module("), excluded);
        assertFalse(addon.contains("new SessionStatsHud("));
        assertTrue(Files.readString(source.resolve(Path.of("modules", "PlayerChunksModule.java")))
            .contains("if (entity == null || position == null) return;"));
    }
}
