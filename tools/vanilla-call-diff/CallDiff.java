/*
 * Call-site census for the NeoFobric bridge.
 *
 * Answers one question across the whole game: where did NeoForge take a vanilla call away? Those are the
 * points a Fabric mod's mixin can end up with nothing to wrap - and therefore the points a "vanilla-shaped
 * door" would have to be opened at. Everything else in a patch (added hooks, added fields, rewritten bodies
 * that keep their calls) needs nothing.
 *
 * Method by method, comparing the clean (unpatched) joined jar with the patched classes:
 *   REROUTED   the patched method still calls something with that name, with other parameters or on another
 *              owner - the platform's own replacement for the call (the door case)
 *   MOVED      the exact call left this method but lives in another method of the same class
 *   GONE       neither - it left the class, so the platform either dropped the work or moved it elsewhere
 *
 * Usage:
 *   java -cp "<out>;<asm>;<asm-tree>" CallDiff <cleanJoined.jar> <patchedClassesDir>[;<more>] [reportFile]
 */

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import java.util.stream.*;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

public class CallDiff {

    /** One vanilla call site that the patched method no longer makes. */
    record Lost(String owner, String name, String desc, String kind, String replacement) {
        String member() {
            return owner + "." + name + desc;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: CallDiff <cleanJoined.jar> <patchedClassesDir>[;<more>] [reportFile]");
            System.exit(2);
        }
        var vanilla = readJar(Path.of(args[0]));
        var patched = new HashMap<String, ClassNode>();
        for (var root : args[1].split(";")) {
            readClasses(Path.of(root), patched);
        }

        var totals = new HashMap<String, Integer>();
        var lostByClass = new TreeMap<String, List<Lost>>();
        var lostTotal = 0;

        for (var entry : vanilla.entrySet()) {
            var patchedNode = patched.get(entry.getKey());
            if (patchedNode == null) {
                continue; // class was removed or is client/server-only
            }
            var patchedMethods = new HashMap<String, MethodNode>();
            for (var method : patchedNode.methods) {
                patchedMethods.put(method.name + method.desc, method);
            }
            var classCalls = new HashSet<String>();
            for (var method : patchedNode.methods) {
                classCalls.addAll(callSites(method));
            }
            for (var vanillaMethod : entry.getValue().methods) {
                var patchedMethod = patchedMethods.get(vanillaMethod.name + vanillaMethod.desc);
                if (patchedMethod == null) {
                    continue; // method removed as a whole - reported by the API diff, not here
                }
                var before = callSitesDeep(vanilla, vanillaMethod, 4);
                var after = callSitesDeep(patched, patchedMethod, 4);
                var lost = new ArrayList<Lost>();
                for (var call : before) {
                    if (after.contains(call)) {
                        continue;
                    }
                    var dot = call.indexOf('.');
                    var owner = call.substring(0, dot);
                    var paren = call.indexOf('(');
                    var name = call.substring(dot + 1, paren);
                    var desc = call.substring(paren);
                    var sameName = after.stream()
                            .filter(site -> site.startsWith(owner + "." + name + "("))
                            .findFirst()
                            .orElse(null);
                    if (sameName != null) {
                        lost.add(new Lost(owner, name, desc, "REROUTED", sameName.substring(sameName.indexOf('('))));
                        continue;
                    }
                    var elsewhere = classCalls.contains(call) ? "MOVED" : "GONE";
                    lost.add(new Lost(owner, name, desc, elsewhere, ""));
                }
                if (lost.isEmpty()) {
                    continue;
                }
                lostTotal += lost.size();
                for (var item : lost) {
                    totals.merge(item.kind(), 1, Integer::sum);
                }
                lostByClass.put(entry.getKey(), lost);
            }
        }

        var summary = "==== " + lostTotal + " vanilla call site(s) dropped across " + lostByClass.size()
                + " class(es); " + totals;
        System.out.println(summary);
        if (args.length > 2) {
            try (var out = Files.newBufferedWriter(Path.of(args[2]))) {
                out.write(summary);
                out.newLine();
                for (var entry : lostByClass.entrySet()) {
                    out.write("---- " + entry.getKey());
                    out.newLine();
                    for (var item : entry.getValue()) {
                        out.write("  " + item.kind() + " " + item.member()
                                + (item.replacement().isEmpty() ? "" : " -> " + item.replacement()));
                        out.newLine();
                    }
                }
            }
            System.out.println("report written to " + args[2]);
        }
    }

    /** {@return the call sites of a method, plus those inside the lambdas it creates} */
    static Set<String> callSitesDeep(Map<String, ClassNode> classes, MethodNode method, int depth) {
        var out = callSites(method);
        if (depth <= 0 || method.instructions == null) {
            return out;
        }
        for (var instruction : method.instructions) {
            if (!(instruction instanceof InvokeDynamicInsnNode indy)) {
                continue;
            }
            for (var argument : indy.bsmArgs) {
                if (!(argument instanceof Handle handle) || !handle.getName().startsWith("lambda$")) {
                    continue;
                }
                var owner = classes.get(handle.getOwner());
                if (owner == null) {
                    continue;
                }
                for (var lambda : owner.methods) {
                    if (lambda.name.equals(handle.getName()) && lambda.desc.equals(handle.getDesc())) {
                        out.addAll(callSitesDeep(classes, lambda, depth - 1));
                    }
                }
            }
        }
        return out;
    }

    static Set<String> callSites(MethodNode method) {
        var out = new HashSet<String>();
        if (method.instructions == null) {
            return out;
        }
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                out.add(call.owner + "." + call.name + call.desc);
            } else if (instruction instanceof InvokeDynamicInsnNode indy) {
                for (var argument : indy.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getName().startsWith("lambda$")) {
                        out.add(handle.getOwner() + "." + handle.getName() + handle.getDesc());
                    }
                }
            }
        }
        return out;
    }

    static Map<String, ClassNode> readJar(Path jar) throws IOException {
        var out = new HashMap<String, ClassNode>();
        try (var zf = new ZipFile(jar.toFile())) {
            for (var entry : zf.stream().filter(e -> e.getName().endsWith(".class")).toList()) {
                try (var in = zf.getInputStream(entry)) {
                    var node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    out.put(node.name, node);
                } catch (Throwable t) {
                    // unreadable class: skipped
                }
            }
        }
        return out;
    }

    static void readClasses(Path root, Map<String, ClassNode> into) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (var file : stream.filter(f -> f.toString().endsWith(".class")).toList()) {
                try (var in = Files.newInputStream(file)) {
                    var node = new ClassNode();
                    new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                    into.put(node.name, node);
                } catch (Throwable t) {
                    // unreadable class: skipped
                }
            }
        }
    }
}
