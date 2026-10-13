/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.fabricbridge;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Proves that a custom payload registered through Fabric's own networking API travels both ways over the
 * bridge, and says so - or names what is missing - in the self-check report.
 *
 * <p>The probe registers two throwaway payload types ({@code neofobric:probe}, client to server, and
 * {@code neofobric:probe_reply}, server to client) through {@code PayloadTypeRegistry}, give them a global
 * receiver on each side, sends one from the client once it is in a world, and answers it from the server.
 * A received reply therefore proves the whole round trip, including the server's receipt - which is why the
 * client-side flag is the authoritative verdict even when the server is a separate process.
 *
 * <p>The payload and its codec are small vanilla-facing classes the bridge cannot compile (this module sees
 * no Minecraft classes), so they are generated with ASM and defined into the fabric target class loader -
 * the same layer as the fabric mods, which is where the registries look them up. Everything else is
 * reflective, and any failure is reported, never thrown.
 */
public final class BridgePayloadProbe {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("NeoFobric/Probe");

    private static final String PAYLOAD = "net/neoforged/fabricbridge/BridgeProbePayload";
    private static final String CODEC = "net/neoforged/fabricbridge/BridgeProbeCodec";
    private static final String PAYLOAD_CLASS = "net.neoforged.fabricbridge.BridgeProbePayload";
    private static final String CODEC_CLASS = "net.neoforged.fabricbridge.BridgeProbeCodec";
    private static final String CUSTOM_PAYLOAD = "net/minecraft/network/protocol/common/custom/CustomPacketPayload";
    private static final String TYPE = "net/minecraft/network/protocol/common/custom/CustomPacketPayload$Type";
    private static final String TYPE_DESC = "L" + TYPE + ";";
    private static final String STREAM_CODEC = "net/minecraft/network/codec/StreamCodec";
    private static final String BUF = "net/minecraft/network/FriendlyByteBuf";

    /** The message of the probe sent this session, or null before it is sent. */
    private static volatile String sentMessage;
    /** Set by the server half when the probe arrives. */
    private static volatile boolean serverSawProbe;
    /** Set by the client half when the reply arrives - the round trip, proven end to end. */
    private static volatile boolean clientSawReply;
    /** The first failure, for the report. */
    private static volatile String failure;

    private static volatile Class<?> payloadClass;
    private static volatile Object probeType;
    private static volatile Object replyType;
    private static volatile Object replyCodec;
    private static volatile boolean installed;
    private static volatile boolean clientReceiverInstalled;
    /** Loads the generated payload and codec in the game layer: its child of the fabric target loader. */
    private static volatile ClassLoader generatedClassLoader;

    private BridgePayloadProbe() {}

    /**
     * Defines the payload classes, registers both types in Fabric's registries and puts the server half's
     * receiver in place. Runs inside the Fabric mod-init window on both sides (see
     * {@link FabricBridge#invokeEntrypoints}).
     */
    public static void install() {
        LOGGER.debug("[NeoFobric] Payload probe install entered (fabricMods={}, installed={})", FabricBridge.hasFabricMods(), installed);
        if (!FabricBridge.hasFabricMods()) {
            return;
        }
        synchronized (BridgePayloadProbe.class) {
            if (installed) {
                return;
            }
            try {
                var loader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
                generatedClassLoader = new GeneratedLoader(loader);
                define(PAYLOAD, generatePayload());
                define(CODEC, generateCodec());

                // Everything is looked up through the generating loader: its parent is the layer the game's
                // payload registries live in (the diagnostic showed the bridge's own classes can come from
                // the system loader, whose vanilla views the registry does not accept).
                payloadClass = Class.forName(PAYLOAD_CLASS, true, generatedClassLoader);
                probeType = payloadClass.getField("PROBE_TYPE").get(null);
                replyType = payloadClass.getField("REPLY_TYPE").get(null);

                var codecClass = Class.forName(CODEC_CLASS, true, generatedClassLoader);
                // Match by name and arity: the type class the codec was generated against and the one a
                // lookup through the fabric loader returns need not be the same Class object.
                var codecCtor = findConstructor(codecClass, 1);
                var probeCodec = codecCtor.newInstance(probeType);
                replyCodec = codecCtor.newInstance(replyType);

                var payloadTypes = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl", false, loader);
                var c2s = payloadTypes.getField("SERVERBOUND_PLAY").get(null);
                var s2c = payloadTypes.getField("CLIENTBOUND_PLAY").get(null);
                registerType(loader, c2s, probeType, probeCodec);
                registerType(loader, s2c, replyType, replyCodec);

                // The server half's receiver answers the probe. ServerPlayNetworking's registration is
                // accepted while the mod init window is open, which is where we are.
                var serverPlay = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking", false, loader);
                var serverHandler = Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$PlayPayloadHandler", false, loader);
                // Resolve the player through the public context interface: the implementation the context
                // arrives as is not public, and reflecting on it fails with IllegalAccessException.
                var serverContext = findMethod(Class.forName("net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking$Context", false, loader), "player", 0);
                findMethod(serverPlay, "registerGlobalReceiver", 2)
                        .invoke(null, probeType, java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] { serverHandler },
                                (proxy, method, args) -> {
                                    if (method.getName().equals("receive")) {
                                        serverSawProbe = true;
                                        try {
                                            var player = serverContext.invoke(args[1]);
                                            var message = payloadClass.getMethod("message").invoke(args[0]);
                                            var reply = findConstructor(payloadClass, 2)
                                                    .newInstance("reply:" + message, replyType);
                                            findMethod(serverPlay, "send", 2).invoke(null, player, reply);
                                        } catch (Throwable t) {
                                            failure = "could not answer: " + t;
                                            LOGGER.warn("[NeoFobric] Payload probe could not answer", t);
                                        }
                                    }
                                    return null;
                                }));
                LOGGER.info("[NeoFobric] Payload probe registered (neofobric:probe, neofobric:probe_reply)");
            } catch (Throwable t) {
                failure = "registration failed: " + t;
                LOGGER.warn("[NeoFobric] Payload probe could not register its channels", t);
            }
            installed = true;
        }
    }

    /**
     * Puts the client half's receiver in place. Fabric wants receivers registered while the client is
     * initialising, which is exactly the fabric client window the bridge runs from
     * ({@link FabricBridge#invokeClientEntrypoints}).
     */
    public static void installClient() {
        if (!FabricBridge.hasFabricMods() || probeType == null || replyType == null) {
            return;
        }
        synchronized (BridgePayloadProbe.class) {
            if (clientReceiverInstalled) {
                return;
            }
            clientReceiverInstalled = true;
            try {
                var loader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
                var clientPlay = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", false, loader);
                var clientHandler = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking$PlayPayloadHandler", false, loader);
                findMethod(clientPlay, "registerGlobalReceiver", 2)
                        .invoke(null, replyType, java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[] { clientHandler },
                                (proxy, method, args) -> {
                                    if (method.getName().equals("receive")) {
                                        clientSawReply = true;
                                    }
                                    return null;
                                }));
            } catch (Throwable t) {
                failure = "client receiver failed: " + t;
                LOGGER.warn("[NeoFobric] Payload probe could not register its client receiver", t);
            }
        }
    }

    /**
     * Sends the probe once the client is in a world (the self-check calls this from its tick listener).
     */
    public static void sendProbeIfReady() {
        LOGGER.debug("[NeoFobric] Payload probe send check: sent={} payloadClass={} probeType={}",
                sentMessage, payloadClass != null, probeType != null);
        if (sentMessage != null || payloadClass == null || probeType == null) {
            return;
        }
        try {
            var loader = net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
            var message = "probe-" + System.currentTimeMillis() % 100000;
            var payload = findConstructor(payloadClass, 2).newInstance(message, probeType);
            var clientPlay = Class.forName("net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking", false, loader);
            findMethod(clientPlay, "send", 1).invoke(null, payload);
            sentMessage = message;
            LOGGER.info("[NeoFobric] Payload probe sent ({})", message);
        } catch (Throwable t) {
            failure = "send failed: " + t;
            sentMessage = "";
            LOGGER.warn("[NeoFobric] Payload probe could not send", t);
        }
    }

    /**
     * {@return the one-line verdict for the self-check report, or null when the probe never ran here}
     */
    public static String verdict() {
        if (sentMessage == null) {
            // Only a client is expected to send the probe; a dedicated server's report stays silent.
            return installed && clientReceiverInstalled
                    ? "fabric payload probe registered but never sent - " + (failure == null ? "no send attempt reached it" : failure)
                    : null;
        }
        if (clientSawReply) {
            return "fabric payload round trip ok (neofobric:probe -> server -> neofobric:probe_reply)";
        }
        if (!sentMessage.isEmpty()) {
            return "fabric payload round trip FAILED on neofobric:probe"
                    + (serverSawProbe ? " (the server saw it, the reply did not arrive)" : " (the server never saw it)")
                    + (failure == null ? "" : " - " + failure);
        }
        return "fabric payload round trip not attempted - " + failure;
    }

    private static void registerType(ClassLoader loader, Object registry, Object type, Object codec) throws Exception {
        var register = findMethod(registry.getClass(), "register", 2);
        var params = register.getParameterTypes();
        LOGGER.debug("[NeoFobric] Payload probe register view: registry={} params={} (loaders {} / {}) type={} (loader {}), codec={} (loader {})",
                registry.getClass().getClassLoader(),
                java.util.Arrays.toString(params),
                params[0].getClassLoader(), params[1].getClassLoader(),
                type.getClass(), type.getClass().getClassLoader(),
                codec.getClass(), codec.getClass().getClassLoader());
        register.invoke(registry, type, codec);
    }

    /** {@return the one-parameter constructor, wherever the parameter class came from} */
    private static java.lang.reflect.Constructor<?> findConstructor(Class<?> owner, int arity) throws NoSuchMethodException {
        for (var ctor : owner.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == arity) {
                return ctor;
            }
        }
        throw new NoSuchMethodException(owner + ".<init> with " + arity + " parameter(s)");
    }

    /** {@return the public method of this name and arity, whatever the parameter classes are} */
    private static java.lang.reflect.Method findMethod(Class<?> owner, String name, int arity) throws NoSuchMethodException {
        for (var method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == arity) {
                return method;
            }
        }
        throw new NoSuchMethodException(owner + "." + name + " with " + arity + " parameter(s)");
    }

    /** Loads the generated classes next to the game layer's own, so their vanilla views are the ones the
     *  payload registries compare against. */
    private static final class GeneratedLoader extends ClassLoader {
        GeneratedLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    private static void define(String name, byte[] bytes) {
        ((GeneratedLoader) generatedClassLoader).define(name.replace('/', '.'), bytes);
    }

    private static byte[] generatePayload() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, PAYLOAD, null, "java/lang/Object", new String[] { CUSTOM_PAYLOAD });

        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "PROBE_TYPE", TYPE_DESC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "REPLY_TYPE", TYPE_DESC, null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "message", "Ljava/lang/String;", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "type", TYPE_DESC, null, null).visitEnd();

        var clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        // PROBE_TYPE = new CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("neofobric", "probe"))
        // (CustomPacketPayload.createType takes a PATH only and would force the minecraft namespace.)
        clinit.visitTypeInsn(Opcodes.NEW, TYPE);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitLdcInsn("neofobric");
        clinit.visitLdcInsn("probe");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/resources/Identifier", "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;", false);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, TYPE, "<init>", "(Lnet/minecraft/resources/Identifier;)V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, PAYLOAD, "PROBE_TYPE", TYPE_DESC);
        clinit.visitTypeInsn(Opcodes.NEW, TYPE);
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitLdcInsn("neofobric");
        clinit.visitLdcInsn("probe_reply");
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/resources/Identifier", "fromNamespaceAndPath",
                "(Ljava/lang/String;Ljava/lang/String;)Lnet/minecraft/resources/Identifier;", false);
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, TYPE, "<init>", "(Lnet/minecraft/resources/Identifier;)V", false);
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, PAYLOAD, "REPLY_TYPE", TYPE_DESC);
        clinit.visitInsn(Opcodes.RETURN);
        clinit.visitMaxs(4, 0);
        clinit.visitEnd();

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/String;" + TYPE_DESC + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, PAYLOAD, "message", "Ljava/lang/String;");
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, PAYLOAD, "type", TYPE_DESC);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(2, 3);
        ctor.visitEnd();

        var message = cw.visitMethod(Opcodes.ACC_PUBLIC, "message", "()Ljava/lang/String;", null, null);
        message.visitCode();
        message.visitVarInsn(Opcodes.ALOAD, 0);
        message.visitFieldInsn(Opcodes.GETFIELD, PAYLOAD, "message", "Ljava/lang/String;");
        message.visitInsn(Opcodes.ARETURN);
        message.visitMaxs(1, 1);
        message.visitEnd();

        var type = cw.visitMethod(Opcodes.ACC_PUBLIC, "type", "()" + TYPE_DESC, null, null);
        type.visitCode();
        type.visitVarInsn(Opcodes.ALOAD, 0);
        type.visitFieldInsn(Opcodes.GETFIELD, PAYLOAD, "type", TYPE_DESC);
        type.visitInsn(Opcodes.ARETURN);
        type.visitMaxs(1, 1);
        type.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] generateCodec() {
        var cw = new ClassWriter(0);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, CODEC, null, "java/lang/Object", new String[] { STREAM_CODEC });

        cw.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "type", TYPE_DESC, null, null).visitEnd();

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + TYPE_DESC + ")V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, CODEC, "type", TYPE_DESC);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(2, 2);
        ctor.visitEnd();

        // StreamDecoder#decode(Object)Object: the payload id is written by the dispatcher, the body is ours.
        var decode = cw.visitMethod(Opcodes.ACC_PUBLIC, "decode", "(Ljava/lang/Object;)Ljava/lang/Object;", null, null);
        decode.visitCode();
        decode.visitTypeInsn(Opcodes.NEW, PAYLOAD);
        decode.visitInsn(Opcodes.DUP);
        decode.visitVarInsn(Opcodes.ALOAD, 1);
        decode.visitTypeInsn(Opcodes.CHECKCAST, BUF);
        decode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUF, "readUtf", "()Ljava/lang/String;", false);
        decode.visitVarInsn(Opcodes.ALOAD, 0);
        decode.visitFieldInsn(Opcodes.GETFIELD, CODEC, "type", TYPE_DESC);
        decode.visitMethodInsn(Opcodes.INVOKESPECIAL, PAYLOAD, "<init>", "(Ljava/lang/String;" + TYPE_DESC + ")V", false);
        decode.visitInsn(Opcodes.ARETURN);
        decode.visitMaxs(4, 2);
        decode.visitEnd();

        // StreamEncoder#encode(Object, Object)V
        var encode = cw.visitMethod(Opcodes.ACC_PUBLIC, "encode", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        encode.visitCode();
        encode.visitVarInsn(Opcodes.ALOAD, 1);
        encode.visitTypeInsn(Opcodes.CHECKCAST, BUF);
        encode.visitVarInsn(Opcodes.ALOAD, 2);
        encode.visitTypeInsn(Opcodes.CHECKCAST, PAYLOAD);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, PAYLOAD, "message", "()Ljava/lang/String;", false);
        encode.visitMethodInsn(Opcodes.INVOKEVIRTUAL, BUF, "writeUtf", "(Ljava/lang/String;)L" + BUF + ";", false);
        encode.visitInsn(Opcodes.POP);
        encode.visitInsn(Opcodes.RETURN);
        encode.visitMaxs(2, 3);
        encode.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
