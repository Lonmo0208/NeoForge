/*
 * Injection-anchor verifier for the NeoFobric bridge.
 *
 * The two shell sweeps in tools/ compare names only: mixin-anchor-sweep.sh checks that the method an
 * injection selects exists somewhere in the target class, mixin-target-sweep.sh that the member its @At
 * points at exists on its owner. Neither checks the combination that actually decides whether an
 * injection fires: **the @At anchor has to sit inside the selected method**. NeoForge replaces whole
 * vanilla bodies, so a method and a callee can both still exist while the call between them is gone -
 * Mixin then skips the injection in production without a word, and the feature the handler drove stops
 * working. That is how Create's StructureTemplateMixin, SignalGetterMixin and LivingEntityMixin died.
 *
 * This tool reads the **patched mod jars** (after the bridge's own annotation realignment, so it sees
 * exactly the annotations Mixin will process) and the **patched game classes** (in dev: the compiled
 * NeoForge tree), and reports, per injection:
 *
 *   OK              the anchor is there (with the ordinal / slice range applied)
 *   DEAD-no-method  the selector matches nothing in the target class
 *   DEAD-no-anchor  the method(s) exist, but the anchor is not inside them
 *   DEAD-partial    a name-only selector matched several overloads and only some carry the anchor
 *   UNCHECKED       anchor kinds this tool does not model (CONSTANT / LOAD / STORE / ...)
 *   ??              the target class is not on the game classpath
 *
 * Build + run (Git Bash, JDK 25, ASM from the Gradle cache) - or just use tools/mixin-anchor-verify.sh:
 *   ASM=<cache>/org.ow2.asm/asm/9.10.1/<hash>/asm-9.10.1.jar
 *   ASMT=<cache>/org.ow2.asm/asm-tree/9.10.1/<hash>/asm-tree-9.10.1.jar
 *   javac -d out -cp "$ASM;$ASMT" VerifyMixinAnchors.java
 *   java -cp "out;$ASM;$ASMT" VerifyMixinAnchors <gameClassesDir>[;<more>] <modJarDir> [onlyFilter]
 *
 * Notes:
 *  - Nested jars are read too (fabric-api ships its modules inside META-INF/jars/).
 *  - @Overwrite is checked as well: it fails hard when its counterpart is gone.
 *  - A descriptor in a selector is honoured when written; without one, the injection must succeed in
 *    every overload Mixin would pick, and one that only succeeds in some is reported as DEAD-partial.
 */

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.stream.*;
import java.util.zip.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class VerifyMixinAnchors {

    /** One injection as written in the annotation: what it selects and where it anchors. */
    record Injection(String mixinClassName, String handler, String kind, List<Selector> selectors,
            Anchor anchor, int annotationLine) {
    }

    /** A method selector: name, and the descriptor when the annotation spelled one out. */
    record Selector(String name, String desc) {
        @Override
        public String toString() {
            return desc == null ? name : name + desc;
        }
    }

    /** The @At: its kind, the member it names, the ordinal and the slice bounds. */
    record Anchor(String value, String owner, String name, String desc, Integer ordinal,
            Anchor sliceFrom, Anchor sliceTo, boolean unmodelled) {
        String memberSpec() {
            var sb = new StringBuilder(value == null ? "?" : value);
            if (owner != null) {
                sb.append(' ').append(owner).append('.').append(name);
            } else if (name != null) {
                sb.append(' ').append(name);
            }
            if (desc != null) {
                sb.append(desc);
            }
            if (ordinal != null) {
                sb.append(" ordinal=").append(ordinal);
            }
            return sb.toString();
        }
    }

    static final Set<String> MIXIN_ANNOTATIONS = Set.of(
            "Lorg/spongepowered/asm/mixin/injection/Inject;",
            // MixinExtras injection kinds share Mixin's method/at shape.
            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;",
            "Lcom/llamalad7/mixinextras/injector/wrapwithcondition/WrapWithCondition;",
            "Lcom/llamalad7/mixinextras/injector/modifyexpressionvalue/ModifyExpressionValue;",
            "Lcom/llamalad7/mixinextras/injector/modifyreceiver/ModifyReceiver;",
            "Lcom/llamalad7/mixinextras/injector/modifyargs/ModifyArgs;");

    /** Annotations that inject but carry no @At (they anchor at the method's exit). */
    static final Set<String> ANCHORLESS_ANNOTATIONS = Set.of(
            "Lcom/llamalad7/mixinextras/injector/modifyreturnvalue/ModifyReturnValue;");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: VerifyMixinAnchors <gameClassesDir>[;<more>] <modJarDirOrJar> "
                    + "[onlySubstring] [extraJarDir][;<more>]");
            System.exit(2);
        }
        var jars = collectJars(Path.of(args[1]), args.length > 4 ? args[4] : "");
        var dirs = Arrays.stream(args[0].split(";")).map(Path::of).filter(Files::isDirectory).toList();
        var only = args.length > 2 && !args[2].isEmpty() ? args[2] : null;
        var extraJars = args.length > 3 ? collectJarsFromDirs(args[3]) : List.<Path>of();
        // Nested jars (fabric-api's modules) are targets for other mods' injections, so they belong on the
        // resolution classpath too, not just in the scan.
        var nested = new ArrayList<Path>();
        var entries = new LinkedHashMap<Path, List<ClassEntry>>();
        var everything = new ArrayList<Path>(jars);
        everything.addAll(extraJars);
        collectAll(jars, entries, nested);
        everything.addAll(nested);
        var resolver = new ClassPath(dirs, everything);

        var ok = 0;
        var skipped = 0;
        var dead = new ArrayList<String>();
        var partial = new ArrayList<String>();
        var unchecked = 0;
        var unknown = new ArrayList<String>();
        var injections = 0;
        var lines = new ArrayList<String>();
        var perJar = new TreeMap<String, int[]>();

        printClasses(resolver, entries, only, lines, perJar);
        for (var line : lines) {
            var verdict = line.startsWith("OK\t") ? State.OK
                    : line.startsWith("SKIP\t") ? State.OK
                            : line.startsWith("UNCHECKED\t") ? State.UNCHECKED
                                    : line.startsWith("UNKNOWN\t") ? State.UNKNOWN
                                            : line.startsWith("PARTIAL\t") ? State.PARTIAL : State.DEAD;
            var detail = line.substring(line.indexOf('\t') + 1);
            injections++;
            switch (verdict) {
                case OK -> {
                    if (line.startsWith("SKIP\t")) {
                        skipped++;
                    } else {
                        ok++;
                    }
                }
                case UNCHECKED -> unchecked++;
                case UNKNOWN -> unknown.add(detail);
                case PARTIAL -> partial.add(detail);
                default -> dead.add(detail);
            }
        }

        System.out.println();
        System.out.println("==== " + injections + " injection(s): " + ok + " ok, " + skipped
                + " in untouched libraries, " + dead.size() + " dead, " + partial.size() + " partial, "
                + unchecked + " unchecked, " + unknown.size() + " unknown target class");
        System.out.println("---- per jar (dead / partial / total)");
        perJar.entrySet().stream()
                .sorted(Comparator.comparingInt((Map.Entry<String, int[]> e) -> -e.getValue()[1]))
                .forEach(e -> System.out.printf("  %-44s %4d / %4d / %4d%n",
                        e.getKey(), e.getValue()[1], e.getValue()[2], e.getValue()[0]));
        if (!dead.isEmpty()) {
            System.out.println("---- DEAD (the anchor is gone; Mixin skips these silently in production)");
            dead.forEach(d -> System.out.println("  " + d));
        }
        if (!partial.isEmpty()) {
            System.out.println("---- PARTIAL (a name-only selector; the anchor is missing in some overloads)");
            partial.forEach(d -> System.out.println("  " + d));
        }
        if (!unknown.isEmpty()) {
            System.out.println("---- unknown target class");
            unknown.forEach(d -> System.out.println("  " + d));
        }
    }

    /** Reads every jar (and the jars nested inside them) once, keeping them for both scan and resolution. */
    static void collectAll(List<Path> jars, Map<Path, List<ClassEntry>> entries, List<Path> nested) {
        for (var jar : jars) {
            if (entries.containsKey(jar)) {
                continue;
            }
            var found = new ArrayList<Path>();
            entries.put(jar, readClasses(jar, found));
            for (var inner : found) {
                if (!entries.containsKey(inner)) {
                    nested.add(inner);
                    collectAll(List.of(inner), entries, nested);
                }
            }
        }
    }

    /** Runs the verification and collects one tab-prefixed verdict line per injection. */
    static void printClasses(ClassPath classpath, Map<Path, List<ClassEntry>> entries, String only,
            List<String> lines, Map<String, int[]> perJar) {
        for (var jarEntry : entries.entrySet()) {
            var jarName = jarEntry.getKey().getFileName().toString();
            var counts = perJar.computeIfAbsent(jarName, k -> new int[3]);
            for (var entry : jarEntry.getValue()) {
                var node = entry.node;
                if (node.visibleAnnotations == null && node.invisibleAnnotations == null) {
                    continue;
                }
                var targets = mixinTargets(node);
                if (targets.isEmpty()) {
                    continue;
                }
                for (var method : node.methods) {
                    var injectionsHere = new ArrayList<Injection>();
                    collectAnnotations(method, injectionsHere, node.name);
                    for (var injection : injectionsHere) {
                        if (only != null && !injection.mixinClassName().contains(only)
                                && !targets.get(0).contains(only)) {
                            continue;
                        }
                        var verdict = verify(classpath, targets, injection);
                        var detail = verdict.detail();
                        var line = switch (verdict.state()) {
                            case OK -> "OK\t" + detail;
                            case UNCHECKED -> "UNCHECKED\t" + detail;
                            case UNKNOWN -> "UNKNOWN\t" + detail;
                            case PARTIAL -> "PARTIAL\t" + detail;
                            default -> "DEAD\t" + detail;
                        };
                        lines.add(line);
                        counts[0]++;
                        if (verdict.state() == State.DEAD) {
                            counts[1]++;
                        } else if (verdict.state() == State.PARTIAL) {
                            counts[2]++;
                        }
                        if (verdict.state() != State.OK || only != null) {
                            System.out.println(line.replace('\t', ' '));
                        }
                    }
                }
            }
        }
    }

    static List<Path> collectJarsFromDirs(String dirs) throws IOException {
        var out = new ArrayList<Path>();
        for (var dir : dirs.split(";")) {
            var path = Path.of(dir);
            if (Files.isDirectory(path)) {
                out.addAll(collectJars(path));
            }
        }
        return out;
    }

    enum State { OK, UNCHECKED, UNKNOWN, DEAD, PARTIAL }

    record Verdict(State state, String detail) {
    }

    // ---------------------------------------------------------------- target classes

    /**
     * The classpath the verifier resolves owners against: the patched game's class directories, plus every
     * scanned jar and the jars of any extra directory (the NeoForge-side mods next to the Fabric ones).
     * Classes in the untouched vanilla libraries are reported as skipped rather than unknown - the
     * platform's patches never reach them, so their anchors cannot have drifted.
     */
    static final class ClassPath {
        private static final List<String> LIBRARY_PREFIXES = List.of(
                "com/mojang/", "com/google/", "org/apache/", "org/jetbrains/", "org/slf4j/", "org/joml/",
                "org/lwjgl/", "org/objectweb/", "io/netty/", "it/unimi/", "javax/", "net/jodah/",
                "net/minecrell/", "net/fabricmc/mappingio/", "com/electronwill/", "org/spongepowered/",
                "com/llamalad7/");

        private final List<Path> dirs;
        private final List<Path> jars;
        private final Map<String, ClassNode> cache = new HashMap<>();
        private Map<String, Path> jarIndex;

        ClassPath(List<Path> dirs, List<Path> jars) {
            this.dirs = dirs;
            this.jars = jars;
        }

        static boolean isLibrary(String internalName) {
            for (var prefix : LIBRARY_PREFIXES) {
                if (internalName.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }

        ClassNode find(String internalName) {
            if (internalName == null) {
                return null;
            }
            if (cache.containsKey(internalName)) {
                return cache.get(internalName);
            }
            ClassNode found = null;
            var file = internalName + ".class";
            for (var dir : dirs) {
                var candidate = dir.resolve(file);
                if (Files.isRegularFile(candidate)) {
                    found = read(candidate);
                    break;
                }
            }
            if (found == null) {
                if (jarIndex == null) {
                    jarIndex = indexJars(jars);
                }
                var jar = jarIndex.get(internalName);
                if (jar != null) {
                    found = readFromJar(jar, file);
                }
            }
            cache.put(internalName, found);
            return found;
        }

        private static ClassNode read(Path file) {
            try (var in = Files.newInputStream(file)) {
                var node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                return node;
            } catch (IOException e) {
                return null;
            }
        }

        private static ClassNode readFromJar(Path jar, String file) {
            try (var zf = new ZipFile(jar.toFile())) {
                var entry = zf.getEntry(file);
                if (entry == null) {
                    return null;
                }
                try (var in = zf.getInputStream(entry)) {
                    var node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    return node;
                }
            } catch (IOException e) {
                return null;
            }
        }

        private static Map<String, Path> indexJars(List<Path> jars) {
            var index = new HashMap<String, Path>();
            for (var jar : jars) {
                try (var zf = new ZipFile(jar.toFile())) {
                    for (var entry : zf.stream().toList()) {
                        var name = entry.getName();
                        if (name.endsWith(".class")) {
                            index.putIfAbsent(name.substring(0, name.length() - ".class".length()), jar);
                        }
                    }
                } catch (IOException e) {
                    // an unreadable jar simply contributes nothing
                }
            }
            return index;
        }

        /** {@return the method plus every supertype method the selector could mean} */
        List<MethodNode> methodsNamed(String internalName, Selector selector) {
            var out = new ArrayList<MethodNode>();
            var visited = new HashSet<String>();
            var queue = new ArrayDeque<String>();
            queue.add(internalName);
            while (!queue.isEmpty()) {
                var name = queue.poll();
                if (!visited.add(name)) {
                    continue;
                }
                var node = find(name);
                if (node == null) {
                    continue;
                }
                for (var method : node.methods) {
                    if (!method.name.equals(selector.name())) {
                        continue;
                    }
                    if (selector.desc() != null && !matchesDescriptor(method.desc, selector.desc())) {
                        continue;
                    }
                    out.add(method);
                }
                if (node.superName != null) {
                    queue.add(node.superName);
                }
                if (node.interfaces != null) {
                    queue.addAll(node.interfaces);
                }
            }
            return out;
        }
    }

    /**
     * {@return whether {@code actual} is the selector's descriptor} The selector may leave the return
     * type off ({@code name(Ldesc;)}) or write it, and Mixin treats {@code ()V} as the void-returning
     * form; both are accepted here.
     */
    static boolean matchesDescriptor(String actual, String wanted) {
        if (actual.equals(wanted)) {
            return true;
        }
        var wantedParams = wanted.substring(0, wanted.indexOf(')') + 1);
        return actual.startsWith(wantedParams);
    }

    // ---------------------------------------------------------------- annotation reading

    /** {@return the internal names of the class this mixin targets} */
    static List<String> mixinTargets(ClassNode node) {
        var out = new ArrayList<String>();
        for (var annotations : List.of(
                node.visibleAnnotations == null ? List.<AnnotationNode>of() : node.visibleAnnotations,
                node.invisibleAnnotations == null ? List.<AnnotationNode>of() : node.invisibleAnnotations)) {
            for (var annotation : annotations) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) {
                    continue;
                }
                var value = annotationValue(annotation, "value");
                if (value instanceof List<?> list) {
                    for (var element : list) {
                        if (element instanceof Type type) {
                            out.add(type.getInternalName());
                        }
                    }
                }
                var targets = annotationValue(annotation, "targets");
                if (targets instanceof List<?> list) {
                    for (var element : list) {
                        if (element instanceof String text) {
                            // Mods write targets either with slashes or as a dotted FQN; Mixin takes both.
                            out.add(text.replace('.', '/'));
                        }
                    }
                }
            }
        }
        return out;
    }

    static void collectAnnotations(MethodNode method, List<Injection> out, String className) {
        var all = new ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) {
            all.addAll(method.visibleAnnotations);
        }
        if (method.invisibleAnnotations != null) {
            all.addAll(method.invisibleAnnotations);
        }
        for (var annotation : all) {
            var simple = annotation.desc.substring(annotation.desc.lastIndexOf('/') + 1,
                    annotation.desc.length() - 1);
            if (MIXIN_ANNOTATIONS.contains(annotation.desc)) {
                // `at` may be a single @At or a list of them; every element has to match, so each one is
                // verified (and reported) on its own.
                var anchors = anchorsOf(annotation);
                for (var selector : selectorsOf(annotation)) {
                    if (anchors.isEmpty()) {
                        out.add(new Injection(className, method.name, simple, List.of(selector), null, 0));
                    }
                    for (var anchor : anchors) {
                        out.add(new Injection(className, method.name, simple, List.of(selector), anchor, 0));
                    }
                }
            } else if (ANCHORLESS_ANNOTATIONS.contains(annotation.desc)) {
                for (var selector : selectorsOf(annotation)) {
                    out.add(new Injection(className, method.name, simple, List.of(selector), null, 0));
                }
            } else if (annotation.desc.equals("Lorg/spongepowered/asm/mixin/Overwrite;")) {
                out.add(new Injection(className, method.name, "Overwrite",
                        List.of(new Selector(method.name, method.desc)), null, 0));
            }
        }
    }

    static List<Selector> selectorsOf(AnnotationNode annotation) {
        var out = new ArrayList<Selector>();
        var value = annotationValue(annotation, "method");
        var raw = new ArrayList<String>();
        if (value instanceof String text) {
            raw.add(text);
        } else if (value instanceof List<?> list) {
            for (var element : list) {
                if (element instanceof String text) {
                    raw.add(text);
                }
            }
        }
        for (var rawText : raw) {
            var text = rawText;
            // Some mods write the selector in full member form (Lowner;name(desc)ret); Mixin takes that
            // too, treating the owner as a further constraint on the target, so keep name+descriptor only.
            if (text.startsWith("L") && text.indexOf(';') > 0) {
                text = text.substring(text.indexOf(';') + 1);
            }
            var open = text.indexOf('(');
            if (open < 0) {
                out.add(new Selector(text, null));
            } else {
                out.add(new Selector(text.substring(0, open), text.substring(open)));
            }
        }
        return out;
    }

    /** {@return every @At the injection lists, or an empty list when it carries none} */
    static List<Anchor> anchorsOf(AnnotationNode annotation) {
        var at = annotationValue(annotation, "at");
        var out = new ArrayList<Anchor>();
        if (at instanceof AnnotationNode node) {
            out.add(anchorFrom(node));
        } else if (at instanceof List<?> list) {
            for (var element : list) {
                if (element instanceof AnnotationNode node) {
                    out.add(anchorFrom(node));
                }
            }
        }
        return out;
    }

    /** {@return the anchor one @At describes, or null when the injection carries no @At at all} */
    static Anchor anchorOf(AnnotationNode annotation) {
        var anchors = anchorsOf(annotation);
        return anchors.isEmpty() ? null : anchors.get(0);
    }

    static Anchor anchorFrom(AnnotationNode node) {
        Anchor sliceFrom = null;
        Anchor sliceTo = null;
        var slice = annotationValue(node, "slice");
        if (slice instanceof AnnotationNode sliceNode) {
            var from = annotationValue(sliceNode, "from");
            var to = annotationValue(sliceNode, "to");
            if (from instanceof AnnotationNode fromNode) {
                sliceFrom = plainAnchor(fromNode);
            }
            if (to instanceof AnnotationNode toNode) {
                sliceTo = plainAnchor(toNode);
            }
        }
        var value = annotationValue(node, "value");
        var target = annotationValue(node, "target");
        var ordinal = annotationValue(node, "ordinal");
        return parseAnchor(value instanceof String s ? s : null,
                target instanceof String s ? s : null,
                ordinal instanceof Integer i ? i : null,
                sliceFrom, sliceTo);
    }

    static Anchor plainAnchor(AnnotationNode node) {
        var value = annotationValue(node, "value");
        var target = annotationValue(node, "target");
        var ordinal = annotationValue(node, "ordinal");
        return parseAnchor(value instanceof String s ? s : null,
                target instanceof String s ? s : null,
                ordinal instanceof Integer i ? i : null, null, null);
    }

    static Object annotationValue(AnnotationNode annotation, String name) {
        if (annotation.values == null) {
            return null;
        }
        for (var i = 0; i + 1 < annotation.values.size(); i += 2) {
            if (name.equals(annotation.values.get(i))) {
                return annotation.values.get(i + 1);
            }
        }
        return null;
    }

    static Anchor parseAnchor(String value, String target, Integer ordinal, Anchor sliceFrom, Anchor sliceTo) {
        // Kinds that anchor on a position in the method carry no member, so a method that exists satisfies
        // them; they only constrain the injection, not its reachability.
        var positionOnly = value == null || Set.of("HEAD", "TAIL", "RETURN").contains(value);
        var unmodelled = !positionOnly && !Set.of("INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW").contains(value);
        if (positionOnly) {
            return new Anchor(value == null ? "default" : value, null, null, null, ordinal, sliceFrom, sliceTo,
                    false);
        }
        if (target == null) {
            return new Anchor(value, null, null, null, ordinal, sliceFrom, sliceTo, true);
        }
        String owner = null;
        String name;
        String desc = null;
        if (target.startsWith("L")) {
            var semi = target.indexOf(';');
            if (semi > 0) {
                owner = target.substring(1, semi);
                var rest = target.substring(semi + 1);
                var open = rest.indexOf('(');
                var colon = rest.indexOf(':');
                if (open >= 0) {
                    name = rest.substring(0, open);
                    desc = rest.substring(open);
                } else if (colon >= 0) {
                    name = rest.substring(0, colon);
                    desc = rest.substring(colon + 1);
                } else {
                    name = rest;
                }
            } else {
                name = target;
            }
        } else if (target.contains("(")) {
            var open = target.indexOf('(');
            name = target.substring(0, open);
            desc = target.substring(open);
        } else if (target.contains(".") && !target.contains("/")) {
            var dot = target.lastIndexOf('.');
            owner = target.substring(0, dot);
            name = target.substring(dot + 1);
        } else {
            name = target;
        }
        return new Anchor(value, owner, name, desc, ordinal, sliceFrom, sliceTo, unmodelled);
    }

    // ---------------------------------------------------------------- verification

    static Verdict verify(ClassPath game, List<String> targets, Injection injection) {
        var where = injection.mixinClassName() + "#" + injection.handler() + " [" + injection.kind() + " "
                + (injection.anchor() == null ? "at exit" : injection.anchor().memberSpec()) + "]";
        var anyTargetKnown = false;
        var anyLibrary = false;
        var deadReasons = new ArrayList<String>();
        var partial = new ArrayList<String>();

        for (var target : targets) {
            if (game.find(target) == null) {
                if (ClassPath.isLibrary(target)) {
                    anyLibrary = true;
                }
                continue;
            }
            anyTargetKnown = true;
            for (var selector : injection.selectors()) {
                if (selector.name().contains("*")) {
                    return new Verdict(State.UNCHECKED, where + " (wildcard selector " + selector + ")");
                }
                var candidates = game.methodsNamed(target, selector);
                if (candidates.isEmpty()) {
                    deadReasons.add(where + " -> " + shortName(target) + ": no method " + selector);
                    continue;
                }
                if (injection.anchor() == null || injection.anchor().unmodelled()) {
                    return new Verdict(State.UNCHECKED, where + " (anchor kind not modelled)");
                }
                var matched = 0;
                var missing = new ArrayList<String>();
                for (var candidate : candidates) {
                    var verdict = matchInMethod(candidate, injection.anchor(), game, 3);
                    if (verdict) {
                        matched++;
                    } else {
                        missing.add(candidate.name + candidate.desc);
                    }
                }
                if (matched == 0) {
                    var host = hostOfAnchor(game, target, injection.anchor());
                    deadReasons.add(where + " -> " + shortName(target) + "." + selector
                            + (host == null
                                    ? ": the @At member is not called/read in that method"
                                    : ": the @At member is not in that method, but " + host
                                            + " carries it (a retarget could repair this)"));
                } else if (matched < candidates.size() && selector.desc() == null) {
                    // A name-only selector matches every overload, and Mixin wants the anchor in each of
                    // them: one overload without it takes the whole injection down.
                    partial.add(where + " -> " + shortName(target) + "." + selector
                            + ": anchor missing in " + missing);
                }
            }
        }
        if (!deadReasons.isEmpty()) {
            return new Verdict(State.DEAD, String.join("\n    ", deadReasons));
        }
        if (!partial.isEmpty()) {
            return new Verdict(State.PARTIAL, String.join("\n    ", partial));
        }
        if (!anyTargetKnown) {
            if (anyLibrary) {
                // Vanilla libraries keep their bodies - the platform patches only the game jar.
                return new Verdict(State.OK, "SKIP " + where + " -> " + targets.get(0) + " (untouched library)");
            }
            return new Verdict(State.UNKNOWN, where + " -> " + targets);
        }
        return new Verdict(State.OK, where);
    }

    /** {@return whether {@code anchor} matches an instruction inside {@code method}} */
    static boolean matchInMethod(MethodNode method, Anchor anchor, ClassPath classpath, int depth) {
        if (anchor.name() == null) {
            // Position-only (HEAD / TAIL / RETURN): the method being there is the whole requirement.
            return true;
        }
        var insns = method.instructions;
        if (insns == null || insns.size() == 0) {
            return false;
        }
        var range = boundsOf(insns, anchor, classpath, depth);
        if (range == null) {
            return false;
        }
        var hits = 0;
        for (var i = range[0]; i <= range[1] && i < insns.size(); i++) {
            if (matches(insns.get(i), anchor)) {
                hits++;
                if (anchor.ordinal() == null || hits > anchor.ordinal()) {
                    return true;
                }
            }
        }
        if (anchor.ordinal() != null) {
            return false;
        }
        // The platform likes to move a call into a lambda the method builds (ItemStack#useOn hands the
        // item call to onItemUse as a lambda, for one), and Mixin follows those: an @At that names a call
        // inside the lambda still fires. So follow the invokedynamic handles the same way, one level at a
        // time, and also treat a handle that *is* the requested member as a match.
        if (depth <= 0) {
            return false;
        }
        for (var instruction : insns) {
            if (!(instruction instanceof InvokeDynamicInsnNode indy)) {
                continue;
            }
            var handles = new ArrayList<Handle>();
            if (indy.bsm != null) {
                handles.add(indy.bsm);
            }
            for (var argument : indy.bsmArgs) {
                if (argument instanceof Handle handle) {
                    handles.add(handle);
                }
            }
            for (var handle : handles) {
                if (matchesHandle(handle, anchor)) {
                    return true;
                }
                if (!handle.getName().startsWith("lambda$")) {
                    continue;
                }
                var owner = classpath.find(handle.getOwner());
                if (owner == null) {
                    continue;
                }
                for (var lambda : owner.methods) {
                    if (lambda.name.equals(handle.getName()) && lambda.desc.equals(handle.getDesc())
                            && matchInMethod(lambda, anchor, classpath, depth - 1)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@return where else in the target class the anchor's member is called, or null}
     *
     * <p>Splitting a vanilla body into a wrapper plus a private method is a NeoForge habit (the tooltip body
     * went into {@code addDetailsToTooltipComponents}/{@code Tail}). An injection that misses its method but
     * whose anchor lives on in a sibling is repairable by pointing the selector at that sibling, which is
     * exactly what the bridge already does for Fabric's tooltip hooks.
     */
    static String hostOfAnchor(ClassPath game, String target, Anchor anchor) {
        var node = game.find(target);
        if (node == null) {
            return null;
        }
        for (var method : node.methods) {
            if (matchInMethod(method, anchor, game, 0)) {
                return shortName(target) + "." + method.name + method.desc;
            }
        }
        return null;
    }

    /** {@return whether a lambda handle points at the member the anchor names} */
    static boolean matchesHandle(Handle handle, Anchor anchor) {
        if (anchor.name() == null || !handle.getName().equals(anchor.name())) {
            return false;
        }
        if (anchor.owner() != null && !handle.getOwner().equals(anchor.owner())) {
            return false;
        }
        var desc = anchor.desc();
        if (desc != null && !handle.getDesc().equals(desc)) {
            // A handle describes the *unbound* method, so a field-like handle or a bound reference can
            // differ in shape; keep the comparison loose for fields.
            return handle.getDesc().contains(desc) || desc.contains(handle.getDesc());
        }
        return true;
    }

    /** {@return the [start, end] instruction range the slice asks for, or null when a bound is absent} */
    static int[] boundsOf(InsnList insns, Anchor anchor, ClassPath classpath, int depth) {
        var start = 0;
        var end = insns.size() - 1;
        if (anchor.sliceFrom() != null) {
            var found = -1;
            for (var i = 0; i < insns.size(); i++) {
                if (matches(insns.get(i), anchor.sliceFrom())) {
                    found = i;
                    break;
                }
            }
            if (found < 0) {
                return null;
            }
            start = found;
        }
        if (anchor.sliceTo() != null) {
            var found = -1;
            for (var i = start; i < insns.size(); i++) {
                if (matches(insns.get(i), anchor.sliceTo())) {
                    found = i;
                }
            }
            if (found < 0) {
                return null;
            }
            end = found;
        }
        return new int[] {start, end};
    }

    static boolean matches(AbstractInsnNode instruction, Anchor anchor) {
        var kind = anchor.value();
        if (kind == null) {
            return true;
        }
        switch (kind) {
            case "INVOKE", "INVOKE_ASSIGN" -> {
                if (!(instruction instanceof MethodInsnNode call)) {
                    return false;
                }
                if (!call.name.equals(anchor.name())) {
                    return false;
                }
                if (anchor.owner() != null && !call.owner.equals(anchor.owner())) {
                    return false;
                }
                return anchor.desc() == null || call.desc.equals(anchor.desc());
            }
            case "FIELD" -> {
                if (!(instruction instanceof FieldInsnNode field)) {
                    return false;
                }
                if (!field.name.equals(anchor.name())) {
                    return false;
                }
                if (anchor.owner() != null && !field.owner.equals(anchor.owner())) {
                    return false;
                }
                return anchor.desc() == null || field.desc.equals(anchor.desc());
            }
            case "NEW" -> {
                if (!(instruction instanceof TypeInsnNode type) || type.getOpcode() != Opcodes.NEW) {
                    return false;
                }
                return anchor.owner() == null || type.desc.equals("L" + anchor.owner() + ";");
            }
            default -> {
                return false;
            }
        }
    }

    static String shortName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    // ---------------------------------------------------------------- input

    record ClassEntry(ClassNode node) {
    }

    static List<ClassEntry> readClasses(Path jar, List<Path> nestedOut) {
        var out = new ArrayList<ClassEntry>();
        try (var zf = new ZipFile(jar.toFile())) {
            for (var entry : zf.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                try (var in = zf.getInputStream(entry)) {
                    var node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    out.add(new ClassEntry(node));
                } catch (Throwable t) {
                    // unreadable class: not this tool's problem
                }
            }
            // fabric-api hides its modules in nested jars, and those carry most of its mixins.
            for (var entry : zf.stream().filter(e -> e.getName().endsWith(".jar")).toList()) {
                var label = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                var tmp = Files.createTempFile("nested-", "-" + label);
                try (var in = zf.getInputStream(entry)) {
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                }
                nestedOut.add(tmp);
                out.addAll(readClasses(tmp, new ArrayList<>()));
            }
        } catch (IOException e) {
            System.err.println("skipping " + jar + ": " + e);
        }
        return out;
    }

    /** {@return the jars to scan; a name suffix narrows a directory to the patched copies, say} */
    static List<Path> collectJars(Path input, String suffix) throws IOException {
        if (Files.isDirectory(input)) {
            try (var stream = Files.list(input)) {
                return stream.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> suffix.isEmpty() || p.getFileName().toString().endsWith(suffix))
                        .sorted()
                        .toList();
            }
        }
        return List.of(input);
    }

    static List<Path> collectJars(Path input) throws IOException {
        return collectJars(input, "");
    }
}
