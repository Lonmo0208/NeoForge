/*
 * Minecraft API diff for the NeoFobric bridge.
 *
 * Compares the vanilla Minecraft jar with the NeoForge-patched one and reports every public/protected
 * member that vanilla has and the patched jar does not (removed) or that changed its descriptor
 * (signature drift, forms one and two in docs/NEOFOBRIC_HANDOFF.md). It also scans a folder of mod jars
 * and marks which of those members the installed Fabric mods actually reference, so fixes can be
 * prioritised: anything in the "*referenced" sections breaks an installed mod, the rest is the risk
 * surface for mods added later.
 *
 * Build + run (Git Bash, JDK 25, ASM from the Gradle cache):
 *   ASM=<gradle-cache>/org.ow2.asm/asm/9.10.1/<hash>/asm-9.10.1.jar
 *   ASMT=<gradle-cache>/org.ow2.asm/asm-tree/9.10.1/<hash>/asm-tree-9.10.1.jar
 *   javac -d out -cp "$ASM;$ASMT" McApiDiff.java
 *   java -cp "out;$ASM;$ASMT" McApiDiff \
 *     ../projects/neoforge/build/neodev/artifacts/clean/joined.jar \
 *     ../projects/neoforge/build/libs/neoforge-<version>-joined.jar \
 *     ../projects/neoforge/run/client/mods report.txt
 *
 * Notes:
 *  - a member whose visibility only narrowed (public -> protected/private) is reported as "narrowed":
 *    that breaks callers at runtime; a widening is ignored because it keeps callers working.
 *  - compiler-generated members (lambda$, access$, ACC_SYNTHETIC) are skipped.
 *  - anonymous classes (ByteBufCodecs$12, ...) shift their numbering whenever a patch adds a lambda,
 *    which shows up as noise; read those lines as "not an API".
 */

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Compares the vanilla Minecraft jar with the NeoForge-patched one and reports every public/protected
 * member that vanilla has and the patched jar does not (removal) or that changed its descriptor
 * (signature drift, 形态一/二 in the handoff docs).
 *
 * It also reads a folder of mod jars and marks which of those members the installed Fabric mods
 * actually reference (constant pool references, plus declared members of mixin classes that carry a
 * mixin annotation - those are the @Shadow/@Overwrite declarations and annotation strings).
 *
 * usage: java Compare <vanillaJoinedJar> <patchedJoinedJar> <modsDir> <outFile>
 */
public class McApiDiff {
    static final int PUBLIC_PROTECTED = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED;

    record Member(String name, String desc, String kind) {}

    /** Class-level facts that break Fabric mods without touching a single member signature. */
    record ClassShape(String superName, boolean isInterface, boolean isFinal) {}

    static Map<String, ClassShape> classShapes(Path jar) throws IOException {
        var result = new TreeMap<String, ClassShape>();
        try (var zf = new JarFile(jar.toFile())) {
            for (var e : zf.stream().filter(x -> x.getName().endsWith(".class")).toList()) {
                var node = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (!node.name.startsWith("net/minecraft/") || isAnonymousClass(node.name)) continue;
                result.put(node.name, new ClassShape(
                        node.superName == null ? "" : node.superName,
                        (node.access & Opcodes.ACC_INTERFACE) != 0,
                        (node.access & Opcodes.ACC_FINAL) != 0));
            }
        }
        return result;
    }

    static Map<String, Set<Member>> members(Path jar) throws IOException {
        var result = new TreeMap<String, Set<Member>>();
        try (var zf = new JarFile(jar.toFile())) {
            for (var e : zf.stream().filter(x -> x.getName().endsWith(".class")).toList()) {
                var cr = new ClassReader(zf.getInputStream(e));
                var node = new ClassNode();
                cr.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                if (!node.name.startsWith("net/minecraft/") || isAnonymousClass(node.name)) continue;
                var set = result.computeIfAbsent(node.name, k -> new TreeSet<>(
                        Comparator.comparing(Member::kind).thenComparing(Member::name).thenComparing(Member::desc)));
                for (var m : node.methods) {
                    if (isSynthetic(m.name, m.access)) continue;
                    set.add(new Member(m.name, m.desc, "M:" + visibility(m.access)));
                }
                for (var f : node.fields) {
                    if (isSynthetic(f.name, f.access)) continue;
                    set.add(new Member(f.name, f.desc, "F:" + visibility(f.access)));
                }
            }
        }
        return result;
    }

    /**
     * { whether the class is an anonymous class (`Foo`, `Foo`)} Their numbering shifts as
     * soon as a patch adds or removes a lambda, so every member of one looks like a removal plus a
     * different member appearing - pure noise, not an API.
     */
    static boolean isAnonymousClass(String internalName) {
        var simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        var lastDollar = simple.lastIndexOf('$');
        if (lastDollar < 0 || lastDollar == simple.length() - 1) return false;
        for (var i = lastDollar + 1; i < simple.length(); i++) {
            if (!Character.isDigit(simple.charAt(i))) return false;
        }
        return true;
    }

    /** compiler-generated helpers (lambdas, synthetic accessors) are not part of either API */
    static boolean isSynthetic(String name, int access) {
        return (access & Opcodes.ACC_SYNTHETIC) != 0 || name.startsWith("lambda$") || name.startsWith("access$");
    }

    static String visibility(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) return "public";
        if ((access & Opcodes.ACC_PROTECTED) != 0) return "protected";
        if ((access & Opcodes.ACC_PRIVATE) != 0) return "private";
        return "package";
    }

    /** everything the given jars reference or expect (mixin declarations + annotation strings) */
    static Map<String, Set<String>> references(Path jarsDir) throws IOException {
        var perClass = new TreeMap<String, Set<String>>();
        var jars = new ArrayList<Path>();
        try (var s = Files.walk(jarsDir)) {
            s.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
        }
        // nested jars too
        for (var jar : new ArrayList<>(jars)) {
            try (var zf = new JarFile(jar.toFile())) {
                for (var e : zf.stream().toList()) {
                    if (e.getName().endsWith(".jar")) {
                        var tmp = Files.createTempFile("nested", ".jar");
                        try (var in = zf.getInputStream(e)) {
                            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                        }
                        jars.add(tmp);
                    }
                }
            } catch (Exception ignored) {}
        }

        for (var jar : jars) {
            try (var zf = new JarFile(jar.toFile())) {
                for (var e : zf.stream().filter(x -> x.getName().endsWith(".class")).toList()) {
                    var refs = new TreeSet<String>();
                    try {
                        var node = new ClassNode();
                        new ClassReader(zf.getInputStream(e)).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        var mixinLike = node.visibleAnnotations != null && node.visibleAnnotations.stream()
                                .anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"));
                        for (var method : node.methods) {
                            for (var insn : method.instructions) {
                                if (insn instanceof MethodInsnNode m) {
                                    refs.add(m.owner + "#" + m.name + "#" + m.desc);
                                } else if (insn instanceof FieldInsnNode f) {
                                    refs.add(f.owner + "#" + f.name + "#" + f.desc);
                                }
                            }
                            if (mixinLike) {
                                // declared member of a mixin class: a @Shadow/@Overwrite needs it on the target
                                refs.add(node.name + "#" + method.name + "#" + method.desc);
                                collectAnnotationStrings(method.visibleAnnotations, refs);
                                collectAnnotationStrings(method.invisibleAnnotations, refs);
                            }
                        }
                        for (var field : node.fields) {
                            if (mixinLike) {
                                refs.add(node.name + "#" + field.name + "#" + field.desc);
                                collectAnnotationStrings(field.visibleAnnotations, refs);
                                collectAnnotationStrings(field.invisibleAnnotations, refs);
                            }
                        }
                        if (mixinLike) {
                            collectAnnotationStrings(node.visibleAnnotations, refs);
                        }
                        if (!refs.isEmpty()) {
                            perClass.computeIfAbsent(jar.getFileName().toString() + "!" + e.getName(),
                                    k -> new TreeSet<>()).addAll(refs);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        return perClass;
    }

    static void collectAnnotationStrings(List<AnnotationNode> annotations, Set<String> out) {
        if (annotations == null) return;
        for (var a : annotations) {
            collectValue(a.values, out);
        }
    }

    static void collectValue(List<Object> values, Set<String> out) {
        if (values == null) return;
        for (var v : values) {
            if (v instanceof String s) out.add(s);
            else if (v instanceof List<?> l) {
                for (var x : l) if (x instanceof String s) out.add(s); else if (x instanceof AnnotationNode an) collectValue(an.values, out);
            } else if (v instanceof AnnotationNode an) collectValue(an.values, out);
        }
    }

    public static void main(String[] args) throws Exception {
        var vanilla = members(Path.of(args[0]));
        var patched = members(Path.of(args[1]));
        var modRefs = references(Path.of(args[2]));
        var vanillaShapes = classShapes(Path.of(args[0]));
        var patchedShapes = classShapes(Path.of(args[1]));

        // Class-level breakage: a Fabric mod that extends one of these breaks on the class itself, before
        // any member matters. NeoForge making a class final is the classic one (iris subclassed
        // OptionInstance), a changed superclass breaks overrides and casts.
        var madeFinal = new ArrayList<String>();
        var superChanged = new ArrayList<String>();
        var interfaceToClass = new ArrayList<String>();
        for (var entry : vanillaShapes.entrySet()) {
            var before = entry.getValue();
            var after = patchedShapes.get(entry.getKey());
            if (after == null) continue;
            if (before.isInterface() != after.isInterface()) {
                interfaceToClass.add(entry.getKey() + " (interface=" + before.isInterface() + " -> " + after.isInterface() + ")");
                continue;
            }
            if (!before.isFinal() && after.isFinal()) {
                madeFinal.add(entry.getKey());
            }
            if (!before.superName().equals(after.superName())) {
                superChanged.add(entry.getKey() + " " + before.superName() + " -> " + after.superName());
            }
        }

        // owner -> member -> referencing mods (matched loosely: exact owner+name, or owner+name+desc)
        var referenced = new HashMap<String, Set<String>>(); // owner + "#" + name -> "desc | mod"
        for (var entry : modRefs.entrySet()) {
            for (var ref : entry.getValue()) {
                var parts = ref.split("#");
                if (parts.length >= 2 && parts[0].startsWith("net/minecraft/")) {
                    referenced.computeIfAbsent(parts[0] + "#" + parts[1], k -> new TreeSet<>())
                            .add((parts.length > 2 ? parts[2] : "?") + " @ " + entry.getKey());
                }
            }
        }

        var removedRef = new ArrayList<String>();
        var removedOther = new ArrayList<String>();
        var driftedRef = new ArrayList<String>();
        var driftedOther = new ArrayList<String>();
        var narrowedRef = new ArrayList<String>();
        var narrowedOther = new ArrayList<String>();
        // Private members are not API, but a Fabric mixin can target them by name (@Shadow, @Inject
        // method="..."), so a rename or a signature change by a patch breaks those too. They are listed
        // separately: restoring one means re-adding a private member with the vanilla name.
        var removedPrivate = new ArrayList<String>();
        var driftedPrivate = new ArrayList<String>();

        for (var entry : vanilla.entrySet()) {
            var owner = entry.getKey();
            var patchedMembers = patched.getOrDefault(owner, Set.of());
            var patchedNames = patchedMembers.stream().collect(Collectors.groupingBy(Member::name,
                    Collectors.mapping(Member::desc, Collectors.toSet())));

            for (var member : entry.getValue()) {
                var vanillaVisibility = visibilityRank(member.kind());
                var refs = referenced.get(owner + "#" + member.name());
                var line = owner + " :: " + member.kind() + " " + member.name() + member.desc();

                var same = patchedMembers.stream()
                        .filter(p -> p.kind().startsWith(member.kind().substring(0, 1))
                                && p.name().equals(member.name()) && p.desc().equals(member.desc()))
                        .toList();
                if (!same.isEmpty()) {
                    // The member is still there; only its visibility may have moved. Widening keeps callers
                    // working, narrowing (NeoForge hiding vanilla API behind private) breaks them at runtime.
                    var patchedVisibility = same.stream().mapToInt(p -> visibilityRank(p.kind())).max().orElse(0);
                    if (vanillaVisibility >= 1 && patchedVisibility < vanillaVisibility) {
                        (refs != null ? narrowedRef : narrowedOther)
                                .add(line + " -> now rank " + patchedVisibility + (refs != null ? "   <== " + refs : ""));
                    }
                    continue;
                }

                var sameName = patchedNames.getOrDefault(member.name(), Set.of());
                if (vanillaVisibility < 1) {
                    // private or package-private in vanilla: only interesting as a mixin target
                    (sameName.isEmpty() ? removedPrivate : driftedPrivate)
                            .add(line + (refs != null ? "   <== " + refs : "")
                                    + (sameName.isEmpty() ? "" : " -> now " + sameName));
                    continue;
                }
                if (sameName.isEmpty()) {
                    (refs != null ? removedRef : removedOther).add(line + (refs != null ? "   <== " + refs : ""));
                } else {
                    (refs != null ? driftedRef : driftedOther).add(line + " -> now " + sameName
                            + (refs != null ? "   <== " + refs : ""));
                }
            }
        }

        try (var w = new PrintWriter(args[3])) {
            w.println("# Reference match = some Fabric mod jar mentions that member.");
            w.println("=== CLASS made final by the patch (a mod extending it breaks): " + madeFinal.size() + " ===");
            madeFinal.forEach(w::println);
            w.println();
            w.println("=== CLASS with a different superclass (overrides/casts break): " + superChanged.size() + " ===");
            superChanged.forEach(w::println);
            w.println();
            w.println("=== CLASS that stopped or started being an interface: " + interfaceToClass.size() + " ===");
            interfaceToClass.forEach(w::println);
            w.println();
            w.println("=== NARROWED and referenced: " + narrowedRef.size() + " ===");
            narrowedRef.forEach(w::println);
            w.println();
            w.println("=== DRIFTED and referenced: " + driftedRef.size() + " ===");
            driftedRef.forEach(w::println);
            w.println();
            w.println("=== REMOVED and referenced: " + removedRef.size() + " ===");
            removedRef.forEach(w::println);
            w.println();
            w.println("=== NARROWED - no mod reference: " + narrowedOther.size() + " ===");
            narrowedOther.forEach(w::println);
            w.println();
            w.println("=== REMOVED - no mod reference: " + removedOther.size() + " ===");
            removedOther.forEach(w::println);
            w.println();
            w.println("=== DRIFTED - no mod reference: " + driftedOther.size() + " ===");
            driftedOther.forEach(w::println);
            w.println();
            w.println("=== REMOVED - private (only a mixin target, not API): " + removedPrivate.size() + " ===");
            removedPrivate.forEach(w::println);
            w.println();
            w.println("=== DRIFTED - private (only a mixin target, not API): " + driftedPrivate.size() + " ===");
            driftedPrivate.forEach(w::println);
        }
        System.out.println("classes: madeFinal=" + madeFinal.size() + " superChanged=" + superChanged.size()
                + " interfaceChanged=" + interfaceToClass.size()
                + " | referenced: narrowed=" + narrowedRef.size() + " drifted=" + driftedRef.size()
                + " removed=" + removedRef.size() + " | unreferenced: narrowed=" + narrowedOther.size()
                + " removed=" + removedOther.size() + " drifted=" + driftedOther.size()
                + " | private: removed=" + removedPrivate.size() + " drifted=" + driftedPrivate.size());
    }

    /** 2 = public, 1 = protected, 0 = private or package-private */
    static int visibilityRank(String kind) {
        if (kind.endsWith("public")) return 2;
        if (kind.endsWith("protected")) return 1;
        return 0;
    }
}
