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
    /** owners that live in libraries rather than in the indexed corpus; not "not reachable" news */
    static final List<String> LIBRARY_PREFIXES = List.of(
            "java/", "javax/", "jdk/", "sun/", "com/mojang/", "it/unimi/", "io/netty/", "com/google/",
            "org/apache/", "org/slf4j/", "org/joml/", "org/spongepowered/", "org/objectweb/",
            "com/llamalad7/", "org/lwjgl/");

    /** class internal name -> "name#desc" of every method and field */
    static final Map<String, Set<String>> MEMBERS = new TreeMap<>();
    /** every class name that exists anywhere we can see */
    static final Set<String> CLASSES = new TreeSet<>();
    /** mixin class -> the class names it targets */
    static final Map<String, List<String>> MIXINS = new TreeMap<>();
    /** "name#desc" members that a mixin class itself declares - injected targets produced at runtime */
    static final Set<String> MIXIN_DEFINED = new TreeSet<>();
    /** how many misses were dropped because the member is mixin-produced */
    static final long[] SYNTHETIC_SKIPPED = new long[1];
    /** how many references were wildcard or empty selectors ("<clinit>*") */
    static final long[] WILDCARD_SKIPPED = new long[1];
    /** class -> superclass (both internal names) */
    static final Map<String, String> SUPER = new TreeMap<>();
    /** class -> its direct interfaces */
    static final Map<String, List<String>> INTERFACES = new TreeMap<>();

    public static void main(String[] args) throws Exception {
        var count = new long[1];
        var all = new ArrayList<Path>();
        try (var s = Files.walk(Path.of(args[1]))) {
            s.filter(p -> p.toString().endsWith(".jar")).forEach(all::add);
        }
        // The bridge's copies of a jar are the artifacts the game loads - .neofobric-realigned.jar for the
        // jars it patches, .neofobric-fml.jar for the fabric jars it hands to FML; the top-level original
        // next to one of them would only report what the bridge already repaired. Skip originals that have
        // a copy (both suffixes).
        var copies = new HashSet<String>();
        for (var p : all) {
            var name = p.getFileName().toString();
            for (var suffix : new String[] { ".neofobric-realigned.jar", ".neofobric-fml.jar" }) {
                if (name.endsWith(suffix)) {
                    copies.add(name.substring(0, name.length() - suffix.length()));
                }
            }
        }
        var jars = new ArrayList<Path>();
        for (var p : all) {
            if (copies.contains(p.getFileName().toString())) {
                continue;
            }
            jars.add(p);
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
            w.println("# scanned " + count[0] + " classes, " + MIXINS.size() + " mixin classes, "
                    + SYNTHETIC_SKIPPED[0] + " miss(es) dropped as mixin-produced, "
                    + WILDCARD_SKIPPED[0] + " wildcard selector(s) skipped");
            w.println();
            w.println("=== MISSING MEMBER: " + missing.size() + " ===");
            missing.forEach(w::println);
            w.println();
            w.println("=== @At TARGET: " + unresolvedTarget.size() + " ===");
            unresolvedTarget.forEach(w::println);
        }
        System.out.println("scanned=" + count[0] + " mixins=" + MIXINS.size() + " missingMembers=" + missing.size()
                + " unresolvedAtTargets=" + unresolvedTarget.size() + " mixinProducedDropped=" + SYNTHETIC_SKIPPED[0]
                + " wildcardsSkipped=" + WILDCARD_SKIPPED[0]);
    }

    static void index(Path jar) {
        try (var zf = new JarFile(jar.toFile())) {
            for (var e : zf.stream().filter(x -> x.getName().endsWith(".class")).toList()) {
                var node = new ClassNode();
                new ClassReader(zf.getInputStream(e)).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                CLASSES.add(node.name);
                if (node.superName != null) {
                    SUPER.put(node.name, node.superName);
                }
                if (node.interfaces != null && !node.interfaces.isEmpty()) {
                    INTERFACES.put(node.name, List.copyOf(node.interfaces));
                }
                var set = MEMBERS.computeIfAbsent(node.name, k -> new TreeSet<>());
                for (var m : node.methods) {
                    set.add(m.name + "#" + m.desc);
                }
                for (var f : node.fields) {
                    set.add(f.name + "#" + f.desc);
                }
                // A mixin's own members are the targets other mixins inject into at runtime (a lambda of a
                // method one mixin adds, for example); the target class does not declare them statically.
                var isMixin = findAnnotation(node.visibleAnnotations, MIXIN) != null
                        || findAnnotation(node.invisibleAnnotations, MIXIN) != null;
                if (isMixin) {
                    for (var m : node.methods) {
                        MIXIN_DEFINED.add(m.name + "#" + m.desc);
                    }
                    for (var f : node.fields) {
                        MIXIN_DEFINED.add(f.name + "#" + f.desc);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** {@return whether the member is declared by a mixin, i.e. produced only when mixins apply} */
    static boolean mixinProduced(String key) {
        if (MIXIN_DEFINED.contains(key)) {
            SYNTHETIC_SKIPPED[0]++;
            return true;
        }
        return false;
    }

    /** {@return whether a mixin declares any member with this name} */
    static boolean mixinProducedName(String name) {
        var prefix = name + "#";
        for (var key : MIXIN_DEFINED) {
            if (key.startsWith(prefix)) {
                SYNTHETIC_SKIPPED[0]++;
                return true;
            }
        }
        return false;
    }

    static void checkClass(String origin, String entry, byte[] bytes, List<String> missing, List<String> unresolvedTarget) {
        var node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        // @Mixin has CLASS retention, so it sits in the invisible list on every real mixin; the visible
        // list is still checked first for the rare mixin that declares it RETENTION_POLICY.RUNTIME.
        var mixin = findAnnotation(node.visibleAnnotations, MIXIN);
        if (mixin == null) {
            mixin = findAnnotation(node.invisibleAnnotations, MIXIN);
        }
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
        targets = indexed(targets);
        if (targets.isEmpty()) {
            return;
        }
        for (var ann : annotations) {
            if (!SHADOW.contains(ann.desc)) {
                continue;
            }
            if (!existsInAny(targets, name + "#" + desc)) {
                missing.add(where + "  @Shadow field " + name + ":" + desc + "  -> " + targets);
            }
        }
    }

    /** {@return the targets this tool can actually resolve members against} */
    static List<String> indexed(List<String> targets) {
        return targets.stream().filter(CLASSES::contains).toList();
    }

    static void handleMember(String where, List<String> targets, String handlerDesc, List<AnnotationNode> annotations,
            List<String> missing, List<String> unresolvedTarget) {
        targets = indexed(targets);
        if (targets.isEmpty()) {
            return;
        }
        for (var ann : annotations) {
            if (SHADOW.contains(ann.desc)) {
                // @Shadow/@Accessor/@Invoker: the member itself must exist on every target. A shadowed
                // method keeps its own name and the handler's descriptor; an accessor reads a field whose
                // descriptor is the handler's return type; an invoker calls a method that keeps the
                // handler's descriptor and is named either explicitly or by the handler.
                var isAccessor = ann.desc.endsWith("/Accessor;");
                var explicit = annotationValues(ann, "value");
                if (explicit.isEmpty() && (isAccessor || ann.desc.endsWith("/Invoker;"))) {
                    // @Accessor/@Invoker derive the member name from the handler's own name (getFoo -> foo);
                    // recomputing that here invites false positives, so they are checked only when the
                    // annotation names the member. @Shadow always uses the handler's name.
                    continue;
                }
                var memberName = explicit.isEmpty()
                        ? handlerDesc.substring(0, handlerDesc.indexOf('('))
                        : explicit.get(0);
                String memberDesc;
                if (isAccessor) {
                    var returnType = org.objectweb.asm.Type.getReturnType(handlerDesc);
                    if (returnType.getSort() == org.objectweb.asm.Type.VOID) {
                        // a setter accessor takes the field's type as its single parameter
                        var arguments = org.objectweb.asm.Type.getArgumentTypes(handlerDesc);
                        memberDesc = arguments.length == 1 ? arguments[0].getDescriptor() : "?";
                    } else {
                        memberDesc = returnType.getDescriptor();
                    }
                } else {
                    memberDesc = handlerDesc.substring(handlerDesc.indexOf('('));
                    if (memberName.equals("<init>")) {
                        // Mixin's @Invoker for a constructor may return the constructed owner instead of
                        // void; the member the target class actually declares still has the void return.
                        memberDesc = memberDesc.substring(0, memberDesc.indexOf(')') + 1) + "V";
                    }
                }
                var memberKey = memberName + "#" + memberDesc;
                if (!existsInAny(targets, memberKey) && !mixinProduced(memberKey)) {
                    missing.add(where + "  " + shortName(ann.desc) + " member " + memberKey + "  -> " + targets);
                }
                continue;
            }
            if (ann.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                // @Overwrite takes no "method=" - the handler itself replaces the member of the same shape.
                var open = handlerDesc.indexOf('(');
                var member = handlerDesc.substring(0, open) + "#" + handlerDesc.substring(open);
                if (!existsInAny(targets, member) && !mixinProduced(member)) {
                    missing.add(where + "  @Overwrite member " + member + "  -> " + targets);
                }
                continue;
            }
            if (!HANDLER.contains(ann.desc)) {
                continue;
            }
            for (var ref : memberRefs(ann)) {
                if (ref.owner() != null || (!ref.atKind().equals("method") && !ref.atKind().equals("target"))) {
                    // A reference that carries its own owner, or one that came from the nested @At (its kind
                    // is the @At selector kind, or "atMethod"), is not a member of the mixin target class:
                    // the @At loop below checks the owned ones, and the nameless descriptor forms for NEW
                    // ("(...)L...;") cannot be resolved statically at all. Checking any of them against the
                    // mixin target was the tool's biggest false positive ("Map.containsKey is not a member
                    // of ComposterBlock").
                    continue;
                }
                if (ref.name() == null) {
                    // a wildcard or empty selector ("<clinit>*", "*"): deliberately loose, nothing to check
                    WILDCARD_SKIPPED[0]++;
                    continue;
                }
                var resolved = ref.desc() == null
                        ? existsByName(targets, ref.name()) || mixinProducedName(ref.name())
                        : existsInAny(targets, ref.name() + "#" + ref.desc()) || mixinProduced(ref.name() + "#" + ref.desc());
                if (!resolved) {
                    missing.add(where + "  " + shortName(ann.desc) + " method=" + ref.name()
                            + (ref.desc() == null ? "" : ref.desc()) + "  -> " + targets);
                }
            }
            // the @At targets of this handler
            for (var at : atAnnotations(ann)) {
                for (var ref : atTargetRefs(at)) {
                    if (ref.owner() == null) {
                        continue;
                    }
                    if (LIBRARY_PREFIXES.stream().anyMatch(p -> ref.owner().startsWith(p))) {
                        continue; // the owner is a library this tool does not index
                    }
                    if (!CLASSES.contains(ref.owner())) {
                        unresolvedTarget.add(where + "  @At(" + ref.atKind() + ") owner " + ref.owner() + " (class not reachable)");
                        continue;
                    }
                    if (ref.name() == null || ref.name().equals("<init>")) {
                        continue; // NEW on a class, or a type-only target
                    }
                    if (!memberExists(ref.owner(), ref.name(), ref.desc())) {
                        if (ref.desc() != null && mixinProduced(ref.name() + "#" + ref.desc())) {
                            continue;
                        }
                        unresolvedTarget.add(where + "  @At(" + ref.atKind() + ") " + ref.owner() + "." + ref.name()
                                + (ref.desc() == null ? "" : ref.desc()) + " (no such member)");
                    }
                }
            }
        }
    }

    record MemberRef(String owner, String name, String desc, String atKind) {}

    /** {@return the member references an @At names - its target (call or field) and its method} */
    static List<MemberRef> atTargetRefs(AnnotationNode at) {
        var result = new ArrayList<MemberRef>();
        var kind = String.valueOf(annotationValues(at, "value").stream().findFirst().orElse("?"));
        for (var value : annotationValues(at, "target")) {
            // parseAtRef, not parseMethodRef: a field target ("Lowner;name:Desc") would otherwise end up
            // with the whole "name:Desc" as the member name and read as missing.
            result.add(parseAtRef(value, kind));
        }
        for (var value : annotationValues(at, "method")) {
            result.add(parseMethodRef(null, value, "atMethod"));
        }
        return result;
    }

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
                // "atMethod" rather than "method": this one belongs to the @At, not to the mixin target
                result.add(parseMethodRef(null, value, "atMethod"));
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
            var colon = text.indexOf(':');
            if (colon >= 0) {
                // the field form "name:Desc"
                return new MemberRef(owner, text.substring(0, colon), text.substring(colon + 1), kind);
            }
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
        var hash = member.indexOf('#');
        if (hash < 0) {
            return false;
        }
        var name = member.substring(0, hash);
        var desc = member.substring(hash + 1);
        for (var target : targets) {
            if (memberExists(target, name, desc)) {
                return true;
            }
        }
        return false;
    }

    static boolean existsByName(List<String> targets, String name) {
        for (var target : targets) {
            if (memberExists(target, name, null)) {
                return true;
            }
        }
        return false;
    }

    /** {@return whether the class or any of its indexed supertypes declares the member} */
    static boolean memberExists(String owner, String name, String desc) {
        var queue = new java.util.ArrayDeque<String>();
        var seen = new java.util.HashSet<String>();
        queue.add(owner);
        while (!queue.isEmpty()) {
            var current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            var members = MEMBERS.get(current);
            if (members != null) {
                if (desc == null) {
                    if (members.stream().anyMatch(m -> m.startsWith(name + "#"))) {
                        return true;
                    }
                } else if (members.contains(name + "#" + desc)) {
                    return true;
                }
            }
            var superName = SUPER.get(current);
            if (superName != null) {
                queue.add(superName);
            }
            var interfaces = INTERFACES.get(current);
            if (interfaces != null) {
                queue.addAll(interfaces);
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
