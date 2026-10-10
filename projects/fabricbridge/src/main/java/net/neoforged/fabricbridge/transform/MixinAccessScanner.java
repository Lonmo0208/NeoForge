/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge.transform;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Detects members that Fabric mixins reach on Minecraft classes but that are private in this tree.
 *
 * <p>Fabric ships its own access wideners for these; NeoForge's access transformer covers a different
 * set, so every gap becomes an {@code IllegalAccessError} while the class is loaded - which surfaces as
 * an opaque startup crash whose stack trace points at a mixin rather than at the missing widening.
 *
 * <p>Two kinds of reference have to be found, because mixins use both:
 * <ul>
 * <li>ordinary bytecode - {@code GETFIELD}/{@code INVOKEVIRTUAL} and friends, seen through
 * {@code visitFieldInsn}/{@code visitMethodInsn};</li>
 * <li>annotation values - {@code @Redirect}/{@code @Inject} name their target inside a string, e.g.
 * {@code Lnet/minecraft/client/renderer/SectionBufferBuilderPool;allocate(I)...}. These never appear as
 * an instruction in the mixin, so a bytecode-only scan misses them entirely; sodium's
 * {@code RenderBuffersMixin} reaches {@code SectionBufferBuilderPool} exactly this way.</li>
 * </ul>
 *
 * <p>When a class is referenced at all, every private member it declares is reported. A class name in an
 * annotation says nothing about which member is used, and widening a member that turns out to be unused
 * is harmless, whereas guessing wrong means another crash per startup.
 */
public final class MixinAccessScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MixinAccessScanner.class);

    private MixinAccessScanner() {}

    /**
     * Package prefixes of the classes a Fabric mod may reach into.
     *
     * <p>Not only {@code net/minecraft/}: the game ships a large part of its engine under
     * {@code com/mojang/} too (blaze3d vertex and pipeline code, serialization, math), and Fabric mods
     * touch those the same way. Scanning only {@code net/minecraft/} is what let sodium's
     * {@code EntityRenderer} reach {@code PoseStack$Pose.trustedNormals} unnoticed, which crashed the
     * client the moment a hand was rendered - long after startup looked healthy.
     */
    private static final List<String> GAME_PREFIXES = List.of(
            "net/minecraft/",
            "com/mojang/");

    /**
     * @param mixinClasses class bytes of the Fabric mixin classes
     * @param gameClasses resolver from internal name to the target class's bytes
     * @return class internal name to the private member names to widen, in discovery order
     */
    public static Map<String, Set<String>> findPrivateMembers(List<byte[]> mixinClasses,
            java.util.function.Function<String, byte[]> gameClasses) {
        var referenced = new TreeSet<String>();
        for (var bytes : mixinClasses) {
            collectReferences(bytes, referenced);
        }

        var result = new LinkedHashMap<String, Set<String>>();
        for (var owner : referenced) {
            if (!isGameClass(owner)) {
                continue;
            }
            var targetBytes = gameClasses.apply(owner);
            if (targetBytes == null) {
                continue;
            }
            // Registered even when the class declares no private members: a direct reference from a
            // Fabric class to a non-public game class (for example sodium reaching the package-private
            // GlDevice) needs the CLASS widened to public, and the widen pass keys off this map.
            result.put(owner, privateMembersOf(targetBytes));
        }
        return result;
    }

    /** Collects the names of every game class the mixin mentions, from instructions and annotations. */
    private static void collectReferences(byte[] bytes, Set<String> out) {
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                    addType(out, desc);
                    return new FieldVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                            return scanning(out);
                        }
                    };
                }

                @Override
                public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                    return scanning(out);
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    addType(out, desc);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public AnnotationVisitor visitAnnotation(String annDesc, boolean visible) {
                            return scanning(out);
                        }

                        @Override
                        public AnnotationVisitor visitParameterAnnotation(int parameter, String annDesc, boolean visible) {
                            return scanning(out);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                            addInternal(out, owner);
                            addType(out, desc);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
                            addInternal(out, owner);
                            addType(out, desc);
                        }

                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            addInternal(out, type);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String text) {
                                collectFromText(text, out);
                            } else if (value instanceof Type type) {
                                addType(out, type.getDescriptor());
                            }
                        }
                    };
                }
            }, 0);
        } catch (Throwable t) {
            // A class we cannot read simply contributes nothing - but the gap is invisible later (the
            // game dies with IllegalAccessError instead), so it must not be silent.
            LOGGER.warn("[NeoFobric] Access scan could not read a class, its references are unscanned: {}", t.toString());
        }
    }

    /**
     * {@return a visitor that pulls member references out of an annotation}
     *
     * <p>Annotation values nest (arrays, nested annotations, class literals), so this returns visitors
     * that recurse through all of them.
     */
    private static AnnotationVisitor scanning(Set<String> out) {
        return new AnnotationVisitor(Opcodes.ASM9) {
            @Override
            public void visit(String name, Object value) {
                if (value instanceof String text) {
                    collectFromText(text, out);
                } else if (value instanceof Type type) {
                    addType(out, type.getDescriptor());
                }
            }

            @Override
            public void visitEnum(String name, String desc, String value) {
                addType(out, desc);
            }

            @Override
            public AnnotationVisitor visitAnnotation(String name, String desc) {
                addType(out, desc);
                return scanning(out);
            }

            @Override
            public AnnotationVisitor visitArray(String name) {
                return scanning(out);
            }
        };
    }

    /**
     * Pulls class names out of a string literal that names an injection target.
     *
     * <p>The shape is a JVM descriptor such as
     * {@code Lnet/minecraft/client/renderer/SectionBufferBuilderPool;allocate(I)Lnet/...;} - the class is
     * the run between {@code L} and {@code ;}, and the descriptor may contain several of them.
     */
    private static void collectFromText(String text, Set<String> out) {
        if (text == null || text.isEmpty()) {
            return;
        }
        var index = 0;
        while (true) {
            var start = nextGameMarker(text, index);
            if (start < 0) {
                return;
            }
            var semi = text.indexOf(';', start);
            var end = semi < 0 ? text.length() : semi;
            addInternal(out, text.substring(start + 1, end));
            index = end;
        }
    }

    /**
     * {@return the index of the next {@code L<class>;} marker for a game class at or after {@code from},
     * or {@code -1} when there is none}
     */
    private static int nextGameMarker(String text, int from) {
        var best = -1;
        for (var prefix : GAME_PREFIXES) {
            var idx = text.indexOf("L" + prefix, from);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static void addInternal(Set<String> out, String internalName) {
        if (internalName != null && isGameClass(internalName)) {
            out.add(internalName);
        }
    }

    /** {@return whether the internal name belongs to a class shipped with the game} */
    private static boolean isGameClass(String internalName) {
        for (var prefix : GAME_PREFIXES) {
            if (internalName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds every object/array component of a descriptor, including method arguments and return type.
     *
     * <p>The descriptor may be a method descriptor ({@code (...)...}) or a field descriptor. Only a
     * method descriptor may be handed to {@link Type#getArgumentTypes(String)} - it walks the string
     * hunting for the closing paren and runs past the end of a field descriptor, so the previous
     * version of this method threw on every class's first field, the exception was swallowed, and
     * the class contributed no references at all. That silent hole is what made the scan "miss"
     * members like GrassColor.pixels (sodium's BiomeColorMaps has fields) while classes without
     * fields scanned fine - i.e. the non-determinism this scanner used to show.
     */
    private static void addType(Set<String> out, String descriptor) {
        if (descriptor == null) {
            return;
        }
        if (descriptor.startsWith("(")) {
            for (var type : Type.getArgumentTypes(descriptor)) {
                addType(out, type);
            }
            addType(out, Type.getReturnType(descriptor));
        } else if (descriptor.startsWith("L")) {
            addInternal(out, descriptor.substring(1, descriptor.length() - 1));
        } else if (descriptor.startsWith("[")) {
            addType(out, descriptor.substring(1));
        }
        // A primitive field descriptor names no class - nothing to record.
    }

    private static void addType(Set<String> out, Type type) {
        var sort = type.getSort();
        if (sort == Type.OBJECT || sort == Type.ARRAY) {
            addType(out, type.getDescriptor());
        }
    }

    /** {@return the names of the private fields and methods the class declares} */
    private static Set<String> privateMembersOf(byte[] bytes) {
        var privates = new TreeSet<String>();
        try {
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                    if ((access & Opcodes.ACC_PRIVATE) != 0) {
                        privates.add(name);
                    }
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    if ((access & Opcodes.ACC_PRIVATE) != 0) {
                        privates.add(name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
        } catch (Throwable ignored) {
            // Unreadable target: treat as having no private members.
        }
        return privates;
    }

    /** Kept for callers that only have a member map to look at. */
    static Map<String, Set<String>> emptyResult() {
        return new TreeMap<>();
    }
}
