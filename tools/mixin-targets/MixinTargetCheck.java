/*
 * Static mixin reference check for the NeoFobric bridge.
 *
 * For every Fabric mod jar in a folder it reads the mixins' annotations and verifies that every member
 * they name actually exists in the patched game: @Inject/@WrapOperation/@ModifyArg/... "method=" targets,
 * the "target" of their @At injection points, and the members @Shadow/@Overwrite require. A reference
 * that does not resolve is an injection that fails silently (mixin relaxes these to defaultRequire 0),
 * and whatever feature sits behind it - an event, a network hook - simply never fires. That list is the
 * work list for the content layer.
 *
 * usage: java -cp "out;<asm>;<asm-tree>" MixinTargetCheck <patchedJoinedJar> <modsDir> <outFile>
 */

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class MixinTargetCheck {
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final Set<String> METHOD_KEYS = Set.of("method", "target");
    /** annotations whose "method=" names the method the handler binds to */
    static final Set<String> HANDLER = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyArgs;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyVariable;",
            "Lorg/spongepowered/asm/mixin/injection/ModifyConstant;",
            "Lorg/spongepowered/asm/mixin/injection/Redirect;",
            "Lorg/spongepowered/asm/mixin/Overwrite;",
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
            "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/ModifyReturnValue;");
    static final Set<String> SHADOW = Set.of(
            "Lorg/spongepowered/asm/mixin/Shadow;",
            "Lorg/spongepowered/asm/mixin/gen/Accessor;",
            "Lorg/spongepowered/asm/mixin/gen/Invoker;");

    /** class internal name -> "name#desc" of every method and field */
    static final Map<String, Set<String>> MEMBERS = new TreeMap<>();
    /** every class name that exists anywhere we can see */
    static final Set<String> CLASSES = new TreeSet<>();
    /** mixin class -> the class names it targets */
    static final Map<String, List<String>> MIXINS = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        var count = new long[1];
        var jars = new ArrayList<Path>();
        try (var s = Files.walk(Path.of(args[1]))) {
            s.filter(p -> p.toString().endsWith(".jar")).forEach(jars::add);
        }
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
            } catch (Throwable ignored) {}
        }

        index(Path.of(args[0]));
        for (var jar : jars) {
            index(jar);
        }

        var missing = new ArrayList<String>();
        var unresolvedTarget = new ArrayList<String>();
        for (var jar : jars) {
            try (var zf = new JarFile(jar.toFile())) {
                var origin = jar.getFileName().toString();
                for (var e : zf.stream().filter(x -> x.getName().endsWith(".class")).toList()) {
                    count[0]++;
                    try {
                        checkClass(origin, e.getName(), zf.getInputStream(e).readAllBytes(), missing, unresolvedTarget);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }

        try (var w = new PrintWriter(args[2])) {
            w.println("# MIXIN REFERENCES THAT DO NOT RESOLVE (silent injections)");
            w.println("# scanned " + count[0] + " classes");
            w.println();
            w.println("=== MISSING MEMBER: " + missing.size() + " ===");
            missing.forEach(w::println);
            w.println();
            w.println("=== @At TARGET: " + unresolvedTarget.size() + " ===");
            unresolvedTarget.forEach(w::println);
        }
        System.out.println("scanned=" + count[0] + " missingMembers=" + missing.size()
                + " unresolvedAtTargets=" + unresolvedTarget.size());
    }

    static void index(Path jar) {
        try (var zf = new JarFile(jar.toFile())) {
            for (var e : zf.stream().filter(x -> x.getName().endsWith(".class")).toList()) {
                var node = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                CLASSES.add(node.name);
                var set = MEMBERS.computeIfAbsent(node.name, k -> new TreeSet<>());
                for (var m : node.methods) {
                    set.add(m.name + "#" + m.desc);
                }
                for (var f : node.fields) {
                    set.add(f.name + "#" + f.desc);
                }
            }
        } catch (Throwable ignored) {}
    }

    static void checkClass(String origin, String entry, byte[] bytes, List<String> missing, List<String> unresolvedTarget) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        var mixin = findAnnotation(node.visibleAnnotations, MIXIN);
        if (mixin == null) {
            return;
        }
        var targets = new ArrayList<String>();
        for (var i = 1; i < mixin.values.size(); i += 2) {
            var key = String.valueOf(mixin.values.get(i - 1));
            var value = mixin.values.get(i);
            if (key.equals("value")) {
                for (var v : asList(value)) {
                    if (v instanceof Type type && type.getSort() == Type.OBJECT) {
                        targets.add(type.getInternalName());
                    }
                }
            } else if (key.equals("targets")) {
                for (var v : asList(value)) {
                    if (v instanceof String text) {
                        targets.add(text.replace('.', '/'));
                    }
                }
            }
        }
        if (targets.isEmpty()) {
            return;
        }
        var where = origin + "!" + entry.replace('/', '.').replace(".class", "");
        MIXINS.put(where, targets);

        for (var method : node.methods) {
            handleMember(where, targets, method.name + method.desc, allAnnotations(method.visibleAnnotations, method.invisibleAnnotations),
                    missing, unresolvedTarget);
        }
        for (var field : node.fields) {
            handleField(where, targets, field.name, field.desc,
                    allAnnotations(field.visibleAnnotations, field.invisibleAnnotations), missing, unresolvedTarget);
        }
    }

    static void handleField(String where, List<String> targets, String name, String desc, List<AnnotationNode> annotations,
            List<String> missing, List<String> unresolvedTarget) {
        for (var ann : annotations) {
            if (!SHADOW.contains(ann.desc)) {
                continue;
            }
            if (!existsInAny(targets, name + "#" + desc)) {
                missing.add(where + "  @Shadow field " + name + ":" + desc + "  -> " + targets);
            }
        }
    }

    static void handleMember(String where, List<String> targets, String handlerDesc, List<AnnotationNode> annotations,
            List<String> missing, List<String> unresolvedTarget) {
        for (var ann : annotations) {
            if (SHADOW.contains(ann.desc)) {
                // @Shadow/@Accessor/@Invoker: the member itself must exist on every target
                var descriptor = "(Lorg/spongepowered/asm/mixin/gen/Accessor;" .equals(ann.desc) ? null : null;
                var name = where.substring(where.lastIndexOf('.') + 1);
                if (!existsInAny(targets, name + "#" + handlerDesc)) {
                    missing.add(where + "  " + shortName(ann.desc) + " member " + name + handlerDesc + "  -> " + targets);
                }
                continue;
            }
            if (!HANDLER.contains(ann.desc)) {
                continue;
            }
            for (var ref : memberRefs(ann)) {
                var resolved = ref.desc() == null
                        ? existsByName(targets, ref.name())
                        : existsInAny(targets, ref.name() + "#" + ref.desc());
                if (!resolved) {
                    missing.add(where + "  " + shortName(ann.desc) + " method=" + ref.name()
                            + (ref.desc() == null ? "" : ref.desc()) + "  -> " + targets);
                }
            }
            // the @At targets of this handler
            for (var at : atAnnotations(ann)) {
                for (var ref : memberRefs(at)) {
                    if (ref.owner() == null) {
                        continue;
                    }
                    if (!CLASSES.contains(ref.owner())) {
                        unresolvedTarget.add(where + "  @At(" + ref.atKind() + ") owner " + ref.owner() + " (class not reachable)");
                        continue;
                    }
                    if (ref.name() == null || ref.name().equals("<init>")) {
                        continue; // NEW on a class, or a type-only target
                    }
                    var members = MEMBERS.getOrDefault(ref.owner(), Set.of());
                    var found = ref.desc() == null
                            ? members.stream().anyMatch(m -> m.startsWith(ref.name() + "#"))
                            : members.contains(ref.name() + "#" + ref.desc());
                    if (!found) {
                        unresolvedTarget.add(where + "  @At(" + ref.atKind() + ") " + ref.owner() + "." + ref.name()
                                + (ref.desc() == null ? "" : ref.desc()) + " (no such member)");
                    }
                }
            }
        }
    }

    record MemberRef(String owner, String name, String desc, String atKind) {}

    static List<MemberRef> memberRefs(AnnotationNode ann) {
        var result = new ArrayList<MemberRef>();
        for (var value : annotationValues(ann, "method")) {
            result.add(parseMethodRef(null, value, "method"));
        }
        for (var value : annotationValues(ann, "target")) {
            result.add(parseMethodRef(null, value, "target"));
        }
        for (var at : atAnnotations(ann)) {
            var kind = String.valueOf(annotationValues(at, "value").stream().findFirst().orElse("?"));
            for (var value : annotationValues(at, "target")) {
                result.add(parseAtRef(value, kind));
            }
            for (var value : annotationValues(at, "method")) {
                result.add(parseMethodRef(null, value, "method"));
            }
        }
        return result;
    }

    /** {@code Lowner;name(desc)ret} or {@code name(desc)ret}; nulls where the form does not carry them */
    static MemberRef parseMethodRef(String fallbackOwner, String value, String kind) {
        var owner = fallbackOwner;
        var text = value;
        if (text.startsWith("L") && text.contains(";")) {
            owner = text.substring(1, text.indexOf(';'));
            text = text.substring(text.indexOf(';') + 1);
            if (text.startsWith(".")) {
                text = text.substring(1);
            }
        }
        if (text.isEmpty() || text.contains("*") || text.contains(" ")) {
            return new MemberRef(owner, null, null, kind);
        }
        var open = text.indexOf('(');
        if (open < 0) {
            return new MemberRef(owner, text, null, kind);
        }
        return new MemberRef(owner, text.substring(0, open), text.substring(open), kind);
    }

    /** {@code Lowner;name:desc} (field), {@code Lowner;name(desc)ret} (method) or {@code Lowner;} */
    static MemberRef parseAtRef(String value, String kind) {
        if (!value.startsWith("L")) {
            return parseMethodRef(null, value, kind);
        }
        var semi = value.indexOf(';');
        if (semi < 0) {
            return new MemberRef(null, null, null, kind);
        }
        var owner = value.substring(1, semi);
        var rest = value.substring(semi + 1);
        if (rest.isEmpty()) {
            return new MemberRef(owner, null, null, kind);
        }
        if (rest.contains(":")) {
            var colon = rest.indexOf(':');
            return new MemberRef(owner, rest.substring(0, colon), rest.substring(colon + 1), kind);
        }
        var open = rest.indexOf('(');
        if (open < 0) {
            return new MemberRef(owner, rest, null, kind);
        }
        return new MemberRef(owner, rest.substring(0, open), rest.substring(open), kind);
    }

    static List<AnnotationNode> atAnnotations(AnnotationNode ann) {
        var result = new ArrayList<AnnotationNode>();
        for (var i = 1; i < ann.values.size(); i += 2) {
            if (!String.valueOf(ann.values.get(i - 1)).equals("at")) {
                continue;
            }
            for (var value : asList(ann.values.get(i))) {
                if (value instanceof AnnotationNode nested) {
                    result.add(nested);
                }
            }
        }
        return result;
    }

    static List<String> annotationValues(AnnotationNode ann, String key) {
        var result = new ArrayList<String>();
        for (var i = 1; i < ann.values.size(); i += 2) {
            if (!String.valueOf(ann.values.get(i - 1)).equals(key)) {
                continue;
            }
            for (var value : asList(ann.values.get(i))) {
                if (value instanceof String text) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return new ArrayList<>(list);
        }
        return List.of(value);
    }

    static boolean existsInAny(List<String> targets, String member) {
        for (var target : targets) {
            if (MEMBERS.getOrDefault(target, Set.of()).contains(member)) {
                return true;
            }
        }
        return false;
    }

    static boolean existsByName(List<String> targets, String name) {
        for (var target : targets) {
            if (MEMBERS.getOrDefault(target, Set.of()).stream().anyMatch(m -> m.startsWith(name + "#"))) {
                return true;
            }
        }
        return false;
    }

    static List<AnnotationNode> allAnnotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) {
            result.addAll(visible);
        }
        if (invisible != null) {
            result.addAll(invisible);
        }
        return result;
    }

    static AnnotationNode findAnnotation(List<AnnotationNode> annotations, String desc) {
        if (annotations == null) {
            return null;
        }
        return annotations.stream().filter(a -> a.desc.equals(desc)).findFirst().orElse(null);
    }

    static String shortName(String desc) {
        var name = desc.substring(desc.lastIndexOf('/') + 1);
        return "@" + name.substring(0, name.length() - 1);
    }
}
