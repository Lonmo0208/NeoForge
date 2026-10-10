package net.neoforged.fabricbridge;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes the two transfer APIs see each other: Fabric's {@code net.fabricmc.fabric.api.transfer.v1}
 * storages/transactions and NeoForge's {@code net.neoforged.neoforge.transfer} handlers/transactions.
 *
 * <p>Both describe the same thing - a resource holder that can insert and extract inside a transaction - but
 * neither can see the other, so a Fabric mod that walks up to a NeoForge machine with an
 * {@code ItemStorage.SIDED} lookup finds nothing, while a NeoForge hopper finds nothing on a Fabric storage.
 * This class closes that gap:
 *
 * <ul>
 *   <li>Item and fluid lookups fall back to the NeoForge capability. That is the direction a Fabric
 *       transport needs (Create finds its neighbours through {@code ItemStorage.SIDED.find}), so a NeoForge
 *       machine - which only registers {@code Capabilities.Item.BLOCK} - becomes reachable as soon as the
 *       lookup has a fallback.</li>
 *   <li>Fabric storages are handed to NeoForge in the same shape, so a NeoForge machine or hopper can pull
 *       from a Fabric-only inventory.</li>
 * </ul>
 *
 * <p>Transactions are not translated, they are <em>shared</em>. {@code MixinJarPatcher} rewrites the four
 * static entry points of Fabric's {@code Transaction} (openOuter, openNested, getCurrentUnsafe,
 * getLifecycle) to call this class, and what those hand back are the very NeoForge transactions a NeoForge
 * machine would have opened. Bridging two stacks instead would be unsound: Fabric storages roll back through
 * {@code SnapshotParticipant} close callbacks while NeoForge handlers roll back through journals, so an
 * aborted transfer would apply on one side only - duplicating or destroying items. One shared stack means one
 * abort rolls back both halves.
 *
 * <p>The bridge compiles without either API on its classpath (see the module's build file), so everything
 * here goes through reflection and {@link Proxy}: Fabric types are implemented as proxies so Fabric mods
 * still see their own interfaces, and the NeoForge side is called through its public API.
 */
public final class FabricTransferHooks {
    private static final Logger LOGGER = LoggerFactory.getLogger("NeoFobric/transfer");

    private static final String FA_TRANSACTION = "net.fabricmc.fabric.api.transfer.v1.transaction.Transaction";
    private static final String FA_CONTEXT = "net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext";
    private static final String FA_LIFECYCLE = FA_TRANSACTION + "$Lifecycle";
    private static final String FA_RESULT = FA_CONTEXT + "$Result";
    private static final String FA_CLOSE_CALLBACK = FA_CONTEXT + "$CloseCallback";
    private static final String FA_OUTER_CLOSE_CALLBACK = FA_CONTEXT + "$OuterCloseCallback";
    private static final String NEO_TRANSACTION = "net.neoforged.neoforge.transfer.transaction.Transaction";
    private static final String NEO_CONTEXT = "net.neoforged.neoforge.transfer.transaction.TransactionContext";

    private static ClassLoader gameLoader() {
        return net.fabricmc.loader.impl.launch.FabricLauncherBase.getLauncher().getTargetClassLoader();
    }

    private static Class<?> load(String name) throws ClassNotFoundException {
        return Class.forName(name, false, gameLoader());
    }

    // ------------------------------------------------------------------ Fabric transaction transplant

    /**
     * {@return a Fabric {@code Transaction} backed by a fresh NeoForge root transaction}
     * <p>Calls arrive from Fabric's {@code Transaction.openOuter()}, which used to start a transaction on
     * Fabric's own thread-local stack.
     */
    public static Object openOuter() {
        try {
            return wrapTransaction(openNeoTransaction(null), 0);
        } catch (Throwable t) {
            throw new IllegalStateException("NeoFobric could not open a NeoForge transaction for Fabric's transfer API", t);
        }
    }

    /**
     * {@return a Fabric {@code Transaction} nested inside {@code parent}}
     * <p>Calls arrive from Fabric's {@code Transaction.openNested(context)}; a null parent means "outer",
     * as in Fabric's own implementation.
     */
    public static Object openNested(Object parent) {
        if (parent == null) {
            return openOuter();
        }
        if (parent instanceof TransactionHandler handler) {
            try {
                return wrapTransaction(openNeoTransaction(handler.neo), handler.depth + 1);
            } catch (Throwable t) {
                throw new IllegalStateException("NeoFobric could not open a nested NeoForge transaction", t);
            }
        }
        var contextHandler = transactionHandlerOf(parent);
        if (contextHandler != null) {
            try {
                return wrapTransaction(openNeoTransaction(contextHandler.neo), contextHandler.depth + 1);
            } catch (Throwable t) {
                throw new IllegalStateException("NeoFobric could not open a nested NeoForge transaction", t);
            }
        }
        throw new IllegalStateException("NeoFobric cannot nest a transaction under "
                + parent.getClass().getName() + ": every Fabric transfer transaction is a NeoForge transaction here, "
                + "so this context did not come from Fabric's transfer API");
    }

    /**
     * {@return the handler behind a Fabric transaction this bridge handed out, or null}
     * <p>Callers hold the proxy (Fabric's own type), not the handler, so both have to be recognised.
     */
    private static TransactionHandler transactionHandlerOf(Object candidate) {
        if (candidate == null || !Proxy.isProxyClass(candidate.getClass())) {
            return null;
        }
        return Proxy.getInvocationHandler(candidate) instanceof TransactionHandler handler ? handler : null;
    }

    /** {@return the currently open transaction as a Fabric context, or null when none is open} */
    public static Object currentUnsafe() {
        try {
            var neo = neoTransactionClass().getMethod("getCurrentOpenedTransaction").invoke(null);
            if (neo == null) {
                return null;
            }
            var depth = (int) neoTransactionClass().getMethod("depth").invoke(neo);
            return wrapTransaction(neo, depth);
        } catch (Throwable t) {
            return null;
        }
    }

    /** {@return Fabric's {@code Transaction.Lifecycle} for the current thread} */
    public static Object lifecycle() {
        try {
            var state = managerState();
            var current = state.currentDepth < 0 ? null : state.stack.get(state.currentDepth);
            String name;
            if (current == null) {
                name = state.processingRootCommit ? "OUTER_CLOSING" : "NONE";
            } else if (current.open) {
                name = "OPEN";
            } else {
                name = current.depth == 0 ? "OUTER_CLOSING" : "CLOSING";
            }
            return lifecycleConstant(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Class<?> neoTransactionClass() throws ClassNotFoundException {
        return load(NEO_TRANSACTION);
    }

    private static Class<?> faContextClass() throws ClassNotFoundException {
        return load(FA_CONTEXT);
    }

    private static Object openNeoTransaction(Object parentContext) throws Exception {
        var type = neoTransactionClass();
        if (parentContext == null) {
            return type.getMethod("openRoot").invoke(null);
        }
        return type.getMethod("open", load(NEO_CONTEXT)).invoke(null, parentContext);
    }

    private static Object lifecycleConstant(String name) throws Exception {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        var constant = Enum.valueOf((Class<? extends Enum>) load(FA_LIFECYCLE), name);
        return constant;
    }

    /** Per-thread transaction state, standing in for the stack Fabric's own manager used to keep. */
    private static final class ManagerState {
        static final ThreadLocal<ManagerState> STATES = ThreadLocal.withInitial(ManagerState::new);

        final List<TransactionHandler> stack = new ArrayList<>();
        final List<Object> outerCloseCallbacks = new ArrayList<>();
        int currentDepth = -1;
        boolean processingRootCommit;
    }

    private static ManagerState managerState() {
        return ManagerState.STATES.get();
    }

    /**
     * A Fabric transaction over a NeoForge one. Fabric's close callbacks live here because they are
     * registered against this object, and the NeoForge transaction is committed or aborted alongside them,
     * so both halves of a transfer end the same way.
     */
    private static final class TransactionHandler implements InvocationHandler {
        final Object neo;
        final int depth;
        /** Fabric's {@code CloseCallback}s, run last-registered-first, matching Fabric's own order. */
        final List<Object> closeCallbacks = new ArrayList<>();
        boolean open = true;
        Object proxy;

        TransactionHandler(Object neo, int depth) {
            this.neo = neo;
            this.depth = depth;
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            switch (method.getName() + method.getParameterCount()) {
                case "commit0":
                    close(true);
                    return null;
                case "abort0":
                case "close0":
                    if (open) {
                        close(false);
                    }
                    return null;
                case "nestingDepth0":
                    // Fabric counts from 0 for the outermost transaction, and its SnapshotParticipant
                    // indexes its snapshots - and looks up the parent frame - with that number, so this has
                    // to be the NeoForge depth as it stands.
                    return depth;
                case "getOpenTransaction1": {
                    var wanted = (Integer) args[0];
                    var state = managerState();
                    if (wanted < 0 || wanted >= state.stack.size() || state.stack.get(wanted) == null) {
                        throw new IllegalStateException("No open transaction at depth " + wanted);
                    }
                    return state.stack.get(wanted).proxy;
                }
                case "addCloseCallback1":
                    requireOpen();
                    closeCallbacks.add(args[0]);
                    return null;
                case "addOuterCloseCallback1":
                    // Fabric only requires an open transaction on this thread, not an open target: a
                    // storage's participant registers one of these while its own transaction is closing.
                    if (managerState().currentDepth < 0) {
                        throw new IllegalStateException("There is no open transaction on this thread.");
                    }
                    managerState().outerCloseCallbacks.add(args[0]);
                    return null;
                case "openNested0":
                    return wrapTransaction(openNeoTransaction(neo), depth + 1);
                case "getLifecycle0":
                    return lifecycleConstant(open ? "OPEN" : "CLOSING");
                default:
                    return defaultOrObject(self, method, args);
            }
        }

        private void requireOpen() {
            if (!open) {
                throw new IllegalStateException("Transaction operation cannot be applied to a closed or closing transaction.");
            }
        }

        /** Mirrors Fabric's close order: callbacks while closing, then Fabric's stack bookkeeping. */
        private void close(boolean committed) throws Exception {
            requireOpen();
            var state = managerState();
            if (state.currentDepth != depth || state.stack.size() <= depth || state.stack.get(depth) != this) {
                throw new IllegalStateException("Transaction is not the current transaction");
            }
            open = false;

            var result = committed ? enumConstant(FA_RESULT, "COMMITTED") : enumConstant(FA_RESULT, "ABORTED");
            RuntimeException failure = null;
            for (var i = closeCallbacks.size() - 1; i >= 0; i--) {
                failure = runCallback(failure, closeCallbacks.get(i), "onClose", 2, proxy, result);
            }
            closeCallbacks.clear();

            // The other half: this is what makes both sides commit or roll back together.
            var type = neoTransactionClass();
            if (committed) {
                type.getMethod("commit").invoke(neo);
            } else {
                type.getMethod("close").invoke(neo);
            }

            state.stack.set(depth, null);
            state.stack.subList(depth, state.stack.size()).clear();
            state.currentDepth = depth - 1;

            if (depth == 0) {
                state.processingRootCommit = true;
                try {
                    for (var i = state.outerCloseCallbacks.size() - 1; i >= 0; i--) {
                        failure = runCallback(failure, state.outerCloseCallbacks.get(i), "afterOuterClose", 1, result);
                    }
                    state.outerCloseCallbacks.clear();
                } finally {
                    state.processingRootCommit = false;
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private RuntimeException runCallback(RuntimeException failure, Object callback, String name, int arity,
                Object... args) {
            try {
                callback.getClass().getMethod(name, methodTypes(arity)).invoke(callback, args);
                return failure;
            } catch (InvocationTargetException e) {
                var cause = e.getCause();
                if (failure == null) {
                    return new RuntimeException("Encountered an exception while invoking a transaction close callback.", cause);
                }
                failure.addSuppressed(cause);
                return failure;
            } catch (Throwable t) {
                if (failure == null) {
                    return new RuntimeException("Encountered an exception while invoking a transaction close callback.", t);
                }
                failure.addSuppressed(t);
                return failure;
            }
        }

        private Class<?>[] methodTypes(int arity) {
            try {
                return arity == 1
                        ? new Class<?>[] { load(FA_RESULT) }
                        : new Class<?>[] { faContextClass(), load(FA_RESULT) };
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private static Object enumConstant(String owner, String name) throws Exception {
        @SuppressWarnings({ "unchecked", "rawtypes" })
        var constant = Enum.valueOf((Class<? extends Enum>) load(owner), name);
        return constant;
    }

    private static Object wrapTransaction(Object neo, int depth) throws Exception {
        var state = managerState();
        for (var existing : state.stack) {
            // Same transaction, same context object: Fabric's own manager hands the same instance back for
            // the same depth, and getOpenTransaction(depth) has to answer with it.
            if (existing != null && existing.neo == neo) {
                return existing.proxy;
            }
        }
        var handler = new TransactionHandler(neo, depth);
        handler.proxy = Proxy.newProxyInstance(gameLoader(), new Class<?>[] { load(FA_TRANSACTION) }, handler);
        while (state.stack.size() <= depth) {
            state.stack.add(null);
        }
        // A transaction opened outside Fabric (by a NeoForge mod, or by this bridge for a simulation) can sit
        // between two of ours, so the whole prefix up to this depth belongs to the current chain.
        state.stack.set(depth, handler);
        state.currentDepth = Math.max(state.currentDepth, depth);
        return handler.proxy;
    }

    private static Object defaultOrObject(Object proxy, Method method, Object[] args) throws Throwable {
        if (method.getDeclaringClass() == Object.class) {
            return switch (method.getName()) {
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                default -> "NeoFobric transfer " + proxy.getClass().getInterfaces()[0].getSimpleName();
            };
        }
        if (method.isDefault()) {
            return InvocationHandler.invokeDefault(proxy, method, args);
        }
        throw new UnsupportedOperationException("NeoFobric transfer bridge does not implement " + method);
    }

    // ------------------------------------------------------------------ the two resource kinds

    /** Everything the bridge needs to speak about one kind of resource (items, fluids). */
    private static final class Kind {
        final boolean fluid;
        final String name;

        Class<?> faVariant;
        Class<?> faStorage;
        Class<?> faSlottedStorage;
        Class<?> faSingleSlotStorage;
        Method faOf;
        Method faBlank;
        Method faType;
        Method faPatch;

        Class<?> neoHandler;
        Object neoCapability;
        Object neoEmptyResource;
        Method neoOf;
        Method neoType;
        Method neoPatch;
        Method neoIsEmpty;

        Method neoSize;
        Method neoResourceAt;
        Method neoAmount;
        Method neoCapacity;
        Method neoInsertAt;
        Method neoExtractAt;
        Method neoInsertAny;
        Method neoExtractAny;

        /** Fabric counts fluids in droplets (81000 to the bucket), NeoForge in mB. Items are 1:1. */
        long neoUnitsPerFabricUnit = 1;

        Kind(boolean fluid, String name) {
            this.fluid = fluid;
            this.name = name;
        }
    }

    private static final Kind ITEMS = new Kind(false, "item");
    private static final Kind FLUIDS = new Kind(true, "fluid");
    private static final AtomicBoolean KINDS_LOADED = new AtomicBoolean();

    private static void loadKinds() throws Exception {
        if (!KINDS_LOADED.compareAndSet(false, true)) {
            return;
        }
        var patch = load("net.minecraft.core.component.DataComponentPatch");
        var resource = load("net.neoforged.neoforge.transfer.resource.Resource");
        var neoContext = load(NEO_CONTEXT);

        ITEMS.faVariant = load("net.fabricmc.fabric.api.transfer.v1.item.ItemVariant");
        FLUIDS.faVariant = load("net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant");
        ITEMS.neoEmptyResource = load("net.neoforged.neoforge.transfer.item.ItemResource").getField("EMPTY").get(null);
        FLUIDS.neoEmptyResource = load("net.neoforged.neoforge.transfer.fluid.FluidResource").getField("EMPTY").get(null);
        for (var kind : new Kind[] { ITEMS, FLUIDS }) {
            kind.faStorage = load("net.fabricmc.fabric.api.transfer.v1.storage.Storage");
            kind.faSlottedStorage = load("net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage");
            kind.faSingleSlotStorage = load("net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage");
            kind.faBlank = kind.faVariant.getMethod("blank");
            kind.faPatch = load("net.fabricmc.fabric.api.transfer.v1.storage.TransferVariant")
                    .getMethod("getComponentsPatch");
            kind.neoHandler = load("net.neoforged.neoforge.transfer.ResourceHandler");

            var resourceClass = kind.neoEmptyResource.getClass();
            kind.neoIsEmpty = resourceClass.getMethod("isEmpty");
            kind.neoType = resourceClass.getMethod(kind.fluid ? "getFluid" : "getItem");
            kind.neoPatch = resourceClass.getMethod("getComponentsPatch");
            kind.neoOf = resourceClass.getMethod("of", kind.fluid
                    ? load("net.minecraft.world.level.material.Fluid")
                    : load("net.minecraft.world.level.ItemLike"), patch);
            kind.faOf = kind.faVariant.getMethod("of", kind.fluid
                    ? load("net.minecraft.world.level.material.Fluid")
                    : load("net.minecraft.world.level.ItemLike"), patch);
            kind.faType = kind.faVariant.getMethod(kind.fluid ? "getFluid" : "getItem");

            kind.neoSize = kind.neoHandler.getMethod("size");
            kind.neoResourceAt = kind.neoHandler.getMethod("getResource", int.class);
            kind.neoAmount = kind.neoHandler.getMethod("getAmountAsLong", int.class);
            kind.neoCapacity = kind.neoHandler.getMethod("getCapacityAsLong", int.class, resource);
            kind.neoInsertAt = kind.neoHandler.getMethod("insert", int.class, resource, int.class, neoContext);
            kind.neoExtractAt = kind.neoHandler.getMethod("extract", int.class, resource, int.class, neoContext);
            kind.neoInsertAny = kind.neoHandler.getMethod("insert", resource, int.class, neoContext);
            kind.neoExtractAny = kind.neoHandler.getMethod("extract", resource, int.class, neoContext);
        }

        ITEMS.neoCapability = load("net.neoforged.neoforge.capabilities.Capabilities$Item").getField("BLOCK").get(null);
        FLUIDS.neoCapability = load("net.neoforged.neoforge.capabilities.Capabilities$Fluid").getField("BLOCK").get(null);

        var bucketVolume = (int) load("net.neoforged.neoforge.fluids.FluidType").getField("BUCKET_VOLUME").get(null);
        var fabricBucket = (long) load("net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants")
                .getField("BUCKET").get(null);
        FLUIDS.neoUnitsPerFabricUnit = fabricBucket / bucketVolume;
    }

    private static Object neoResourceOf(Kind kind, Object variant) throws Exception {
        return kind.neoOf.invoke(null, kind.faType.invoke(variant), kind.faPatch.invoke(variant));
    }

    private static Object fabricVariantOf(Kind kind, Object resource) {
        try {
            if (resource == null || (boolean) kind.neoIsEmpty.invoke(resource)) {
                return kind.faBlank.invoke(null);
            }
            return kind.faOf.invoke(null, kind.neoType.invoke(resource), kind.neoPatch.invoke(resource));
        } catch (Throwable t) {
            throw new IllegalStateException("NeoFobric could not convert a NeoForge " + kind.name + " resource to a Fabric variant", t);
        }
    }

    /**
     * {@return the NeoForge transaction behind a Fabric context, or null for a simulation}
     * <p>A null Fabric context is Fabric's own way of saying "do not commit this": {@code StorageUtil}'s
     * simulate helpers pass null, and a Fabric storage then rolls its own changes back. The caller of this
     * method opens a scratch transaction for that case and aborts it.
     */
    private static Object neoContextOf(Object fabricContext) {
        if (fabricContext == null) {
            return null;
        }
        var handler = transactionHandlerOf(fabricContext);
        if (handler != null) {
            return handler.neo;
        }
        throw new IllegalStateException("NeoFobric cannot apply a transfer in "
                + fabricContext.getClass().getName() + ": Fabric's transfer transactions are NeoForge transactions here, "
                + "so this context came from somewhere else");
    }

    // ------------------------------------------------------------------ adapting a NeoForge handler

    /** {@return a Fabric storage over a NeoForge resource handler, with one Fabric slot per NeoForge index} */
    private static Object fabricStorageOf(Kind kind, Object handler) {
        var storageHandler = new StorageHandler(kind, handler);
        var storage = Proxy.newProxyInstance(gameLoader(), new Class<?>[] { kind.faSlottedStorage }, storageHandler);
        storageHandler.storage = storage;
        return storage;
    }

    private static final class StorageHandler implements InvocationHandler {
        final Kind kind;
        final Object neoHandler;
        final List<Object> slots = new ArrayList<>();
        Object storage;

        StorageHandler(Kind kind, Object neoHandler) {
            this.kind = kind;
            this.neoHandler = neoHandler;
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            switch (method.getName() + method.getParameterCount()) {
                case "getSlotCount0":
                    return slotCount();
                case "getSlot1":
                    return slot((Integer) args[0]);
                case "insert3":
                    return insert(args[0], (Long) args[1], args[2]);
                case "extract3":
                    return extract(args[0], (Long) args[1], args[2]);
                case "iterator0": {
                    var views = new ArrayList<>(slotCount());
                    for (var index = 0; index < slotCount(); index++) {
                        views.add(slot(index));
                    }
                    return views.iterator();
                }
                case "supportsInsertion0":
                case "supportsExtraction0":
                    return true;
                case "getVersion0":
                    return 0L;
                default:
                    return defaultOrObject(self, method, args);
            }
        }

        private int slotCount() throws Exception {
            return (int) kind.neoSize.invoke(neoHandler);
        }

        private Object slot(int index) throws Exception {
            while (slots.size() <= index) {
                slots.add(null);
            }
            var cached = slots.get(index);
            if (cached == null) {
                cached = Proxy.newProxyInstance(gameLoader(), new Class<?>[] { kind.faSingleSlotStorage },
                        new SlotHandler(kind, neoHandler, index));
                slots.set(index, cached);
            }
            return cached;
        }

        private long insert(Object variant, long maxAmount, Object fabricContext) throws Exception {
            return transfer(kind.neoInsertAny, neoResourceOf(kind, variant), maxAmount, fabricContext);
        }

        private long extract(Object variant, long maxAmount, Object fabricContext) throws Exception {
            return transfer(kind.neoExtractAny, neoResourceOf(kind, variant), maxAmount, fabricContext);
        }

        private long transfer(Method neoMethod, Object resource, long maxAmount, Object fabricContext) throws Exception {
            var amount = longToNeo(kind, maxAmount);
            var neo = neoContextOf(fabricContext);
            if (neo != null) {
                return neoToLong(kind, (int) neoMethod.invoke(neoHandler, resource, amount, neo));
            }
            // A simulated operation still has to run inside a transaction, because NeoForge handlers journal
            // their changes; it must never be committed, so the scratch transaction is always closed.
            var scratch = openNeoTransaction(null);
            try {
                return neoToLong(kind, (int) neoMethod.invoke(neoHandler, resource, amount, scratch));
            } finally {
                neoTransactionClass().getMethod("close").invoke(scratch);
            }
        }
    }

    /** One NeoForge index, seen as a Fabric single slot storage. */
    private static final class SlotHandler implements InvocationHandler {
        final Kind kind;
        final Object neoHandler;
        final int index;

        SlotHandler(Kind kind, Object neoHandler, int index) {
            this.kind = kind;
            this.neoHandler = neoHandler;
            this.index = index;
        }

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            switch (method.getName() + method.getParameterCount()) {
                case "getSlotCount0":
                    return 1;
                case "getSlot1":
                    return self;
                case "getResource0":
                    return fabricVariantOf(kind, resourceAt());
                case "getAmount0":
                    return neoToLong(kind, (long) kind.neoAmount.invoke(neoHandler, index));
                case "isResourceBlank0":
                    return (boolean) kind.neoIsEmpty.invoke(resourceAt());
                case "getCapacity0":
                    return neoToLong(kind, (long) kind.neoCapacity.invoke(neoHandler, index, resourceAt()));
                case "insert3":
                    return transfer(kind.neoInsertAt, args[0], (Long) args[1], args[2]);
                case "extract3":
                    return transfer(kind.neoExtractAt, args[0], (Long) args[1], args[2]);
                case "iterator0":
                    return List.of(self).iterator();
                case "supportsInsertion0":
                case "supportsExtraction0":
                    return true;
                case "getVersion0":
                    return 0L;
                case "getUnderlyingView0":
                    return self;
                default:
                    return defaultOrObject(self, method, args);
            }
        }

        private Object resourceAt() throws Exception {
            return kind.neoResourceAt.invoke(neoHandler, index);
        }

        private long transfer(Method neoMethod, Object variant, long maxAmount, Object fabricContext) throws Exception {
            var amount = longToNeo(kind, maxAmount);
            var neo = neoContextOf(fabricContext);
            if (neo != null) {
                return neoToLong(kind, (int) neoMethod.invoke(neoHandler, index, neoResourceOf(kind, variant), amount, neo));
            }
            var scratch = openNeoTransaction(null);
            try {
                return neoToLong(kind, (int) neoMethod.invoke(neoHandler, index, neoResourceOf(kind, variant), amount, scratch));
            } finally {
                neoTransactionClass().getMethod("close").invoke(scratch);
            }
        }
    }

    private static int longToNeo(Kind kind, long amount) {
        var units = amount / kind.neoUnitsPerFabricUnit;
        return units > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) units;
    }

    private static long neoToLong(Kind kind, long amount) {
        return amount * kind.neoUnitsPerFabricUnit;
    }

    // ------------------------------------------------------------------ wiring

    private static volatile boolean installed;

    /**
     * Registers the NeoForge fallback on both sided lookups. Runs once, after the Fabric API's transfer
     * module has registered its own providers - those still win, a fallback only answers when nothing else
     * knows the block.
     */
    public static void install() {
        if (installed || !FabricBridgeHooks.bridgeActive()) {
            return;
        }
        installed = true;
        try {
            loadKinds();
        } catch (Throwable t) {
            LOGGER.info("[NeoFobric] Fabric's transfer API is not present, skipping the NeoForge transfer bridge");
            return;
        }
        try {
            registerFallback(ITEMS, "net.fabricmc.fabric.api.transfer.v1.item.ItemStorage", "SIDED");
            registerFallback(FLUIDS, "net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage", "SIDED");
            LOGGER.info("[NeoFobric] Fabric's item and fluid lookups now fall back to the NeoForge capabilities");
        } catch (Throwable t) {
            LOGGER.warn("[NeoFobric] Could not register the NeoForge transfer fallbacks", t);
        }
    }

    private static void registerFallback(Kind kind, String lookupOwner, String lookupField) throws Exception {
        var sided = load(lookupOwner).getField(lookupField).get(null);
        var provider = load("net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup$BlockApiProvider");
        var proxy = Proxy.newProxyInstance(gameLoader(), new Class<?>[] { provider }, (self, method, args) -> {
            if (args == null || args.length < 5) {
                return defaultOrObject(self, method, args);
            }
            return findNeoStorage(kind, args[0], args[1], args[2], args[3], args[4]);
        });
        load("net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup")
                .getMethod("registerFallback", provider)
                .invoke(sided, proxy);
    }

    /**
     * {@return a Fabric storage for the NeoForge capability at this position, or null when there is none}
     * <p>This is what a Fabric lookup lands on when nothing else knows the block.
     */
    private static Object findNeoStorage(Kind kind, Object level, Object pos, Object state, Object blockEntity,
            Object side) {
        try {
            if (level == null || pos == null) {
                return null;
            }
            var capabilityClass = load("net.neoforged.neoforge.capabilities.BlockCapability");
            var handler = capabilityClass.getMethod("getCapability", load("net.minecraft.world.level.Level"),
                            load("net.minecraft.core.BlockPos"), load("net.minecraft.world.level.block.state.BlockState"),
                            load("net.minecraft.world.level.block.entity.BlockEntity"), Object.class)
                    .invoke(kind.neoCapability, level, pos, state, blockEntity, side);
            return handler == null ? null : fabricStorageOf(kind, handler);
        } catch (Throwable t) {
            LOGGER.debug("[NeoFobric] NeoForge {} capability lookup failed", kind.name, t);
            return null;
        }
    }

    // ------------------------------------------------------------------ probe

    private static volatile boolean probeDone;

    /**
     * Runs {@link #selfTest()} once, from the self-check's client tick. The probe needs item stacks, and
     * item components are only bound after the registries close - which the bridge defers past the mod
     * entrypoints - so it cannot run while mods are still initializing.
     */
    public static void probeInWorldOnce() {
        if (probeDone || !Boolean.getBoolean("neofobric.transferProbe")) {
            return;
        }
        probeDone = true;
        selfTest();
    }

    /**
     * Checks the bridge against a real NeoForge handler - a journalled one, so an aborted Fabric transaction
     * has to leave it untouched. That is the property a Fabric transport relies on, and the reason the two
     * transaction stacks are shared instead of bridged. Enabled with {@code -Dneofobric.transferProbe=true}.
     */
    private static void selfTest() {
        var failures = new ArrayList<String>();
        try {
            loadKinds();
            // A vanilla container is the most realistic NeoForge handler there is: it journals through
            // ItemStackResourceHandler, so a rollback is observable as the container's contents.
            var simpleContainer = load("net.minecraft.world.SimpleContainer");
            var container = simpleContainer.getConstructor(int.class).newInstance(2);
            var handler = load("net.neoforged.neoforge.transfer.item.VanillaContainerWrapper")
                    .getMethod("of", load("net.minecraft.world.Container")).invoke(null, container);
            var storage = fabricStorageOf(ITEMS, handler);

            var transactionClass = load(FA_TRANSACTION);
            var openOuter = transactionClass.getMethod("openOuter");
            var commit = transactionClass.getMethod("commit");
            var abort = transactionClass.getMethod("abort");
            var openNested = transactionClass.getMethod("openNested", faContextClass());
            var faStorage = load("net.fabricmc.fabric.api.transfer.v1.storage.Storage");
            var insert = faStorage.getMethod("insert", Object.class, long.class, faContextClass());
            var extract = faStorage.getMethod("extract", Object.class, long.class, faContextClass());
            var stack = itemStack("minecraft:stone", 64);

            // 1. A committed insert reaches the NeoForge handler.
            var first = openOuter.invoke(null);
            var inserted = (long) insert.invoke(storage, itemVariant(stack), 64L, first);
            commit.invoke(first);
            if (inserted != 64 || count(container, 0) != 64) {
                failures.add("committed insert: inserted=" + inserted + " slot=" + count(container, 0) + " (want 64/64)");
            }

            // 2. An aborted insert leaves the container alone - the whole point of sharing the transaction.
            var second = openOuter.invoke(null);
            insert.invoke(storage, itemVariant(stack), 64L, second);
            abort.invoke(second);
            if (count(container, 0) != 64) {
                failures.add("aborted insert changed the container: " + count(container, 0) + " (want 64)");
            }

            // 3. Aborting an outer transaction rolls back what a committed inner one did.
            var third = openOuter.invoke(null);
            insert.invoke(storage, itemVariant(stack), 5L, third);
            var nested = openNested.invoke(null, third);
            insert.invoke(storage, itemVariant(stack), 5L, nested);
            commit.invoke(nested);
            abort.invoke(third);
            if (count(container, 0) != 64) {
                failures.add("abort after an inner commit changed the container: " + count(container, 0) + " (want 64)");
            }

            // 4. Extraction, committed and aborted.
            var fourth = openOuter.invoke(null);
            var taken = (long) extract.invoke(storage, itemVariant(stack), 20L, fourth);
            commit.invoke(fourth);
            if (taken != 20 || count(container, 0) != 44) {
                failures.add("committed extract: taken=" + taken + " slot=" + count(container, 0) + " (want 20/44)");
            }
            var fifth = openOuter.invoke(null);
            extract.invoke(storage, itemVariant(stack), 20L, fifth);
            abort.invoke(fifth);
            if (count(container, 0) != 44) {
                failures.add("aborted extract changed the container: " + count(container, 0) + " (want 44)");
            }

            // 5. A null transaction is Fabric's way of asking "what if": nothing may be applied.
            var simulated = (long) insert.invoke(storage, itemVariant(stack), 8L, (Object) null);
            if (simulated != 8 || count(container, 0) != 44) {
                failures.add("simulated insert applied: inserted=" + simulated + " slot=" + count(container, 0)
                        + " (want 8/44)");
            }

            // 6. The slotted view reads the same thing the handler does, and the storage is slotted at all
            // (Fabric consumers take a different path for those).
            var slotted = load("net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage");
            var isSlotted = slotted.isInstance(storage);
            var slotCount = (int) slotted.getMethod("getSlotCount").invoke(storage);
            var slot = (Object) slotted.getMethod("getSlot", int.class).invoke(storage, 0);
            var slotAmount = (long) load("net.fabricmc.fabric.api.transfer.v1.storage.StorageView")
                    .getMethod("getAmount").invoke(slot);
            if (!isSlotted || slotCount != 2 || slotAmount != 44) {
                failures.add("slotted view: slotted=" + isSlotted + " count=" + slotCount + " amount=" + slotAmount
                        + " (want true/2/44)");
            }

            // 7. In the world: wherever NeoForge reports an item capability, Fabric's lookup has to see a
            // storage too - that is the property a Fabric transport needs to reach a NeoForge machine. The
            // fallback's own count separates the blocks only this bridge knows about from the ones Fabric
            // already had a provider for.
            sweepWorld(failures);
        } catch (Throwable t) {
            var chain = new StringBuilder();
            for (var cause = t; cause != null; cause = cause.getCause()) {
                chain.append(cause).append(cause.getCause() == null ? "" : " <- ");
            }
            failures.add("probe threw " + chain);
            LOGGER.error("[NeoFobric] transfer probe threw", t);
        }
        report(failures);
    }

    /**
     * Walks the blocks around the player and checks the two things a Fabric transport depends on: every
     * block NeoForge calls an inventory is one Fabric can see, and every block that only this bridge knows
     * about is answered by the fallback rather than by nothing at all.
     */
    private static void sweepWorld(List<String> failures) throws Exception {
        var minecraft = load("net.minecraft.client.Minecraft").getMethod("getInstance").invoke(null);
        var player = minecraft.getClass().getField("player").get(minecraft);
        if (player == null) {
            return;
        }
        var level = player.getClass().getMethod("level").invoke(player);
        var center = (Object) player.getClass().getMethod("blockPosition").invoke(player);
        var centerX = (int) center.getClass().getMethod("getX").invoke(center);
        var centerY = (int) center.getClass().getMethod("getY").invoke(center);
        var centerZ = (int) center.getClass().getMethod("getZ").invoke(center);

        var blockPos = load("net.minecraft.core.BlockPos");
        var constructor = blockPos.getConstructor(int.class, int.class, int.class);
        var getBlockEntity = level.getClass().getMethod("getBlockEntity", blockPos);
        var getBlockState = level.getClass().getMethod("getBlockState", blockPos);
        var side = Enum.valueOf(load("net.minecraft.core.Direction").asSubclass(Enum.class), "UP");
        var capabilityLookup = load("net.neoforged.neoforge.capabilities.BlockCapability")
                .getMethod("getCapability", load("net.minecraft.world.level.Level"), blockPos,
                        load("net.minecraft.world.level.block.state.BlockState"),
                        load("net.minecraft.world.level.block.entity.BlockEntity"),
                        Object.class);
        var find = load("net.fabricmc.fabric.api.lookup.v1.block.BlockApiLookup")
                .getMethod("find", load("net.minecraft.world.level.Level"), blockPos,
                        load("net.minecraft.world.level.block.state.BlockState"),
                        load("net.minecraft.world.level.block.entity.BlockEntity"), Object.class);
        var lookup = load("net.fabricmc.fabric.api.transfer.v1.item.ItemStorage").getField("SIDED").get(null);

        var neoInventories = 0;
        var fabricSees = 0;
        var onlyThroughBridge = 0;
        for (var dx = -16; dx <= 16; dx++) {
            for (var dy = -16; dy <= 16; dy++) {
                for (var dz = -16; dz <= 16; dz++) {
                    var pos = constructor.newInstance(centerX + dx, centerY + dy, centerZ + dz);
                    var blockEntity = getBlockEntity.invoke(level, pos);
                    if (blockEntity == null) {
                        continue;
                    }
                    var state = getBlockState.invoke(level, pos);
                    if (capabilityLookup.invoke(ITEMS.neoCapability, level, pos, state, blockEntity, side) == null) {
                        continue;
                    }
                    neoInventories++;
                    var storage = find.invoke(lookup, level, pos, state, blockEntity, side);
                    if (storage != null) {
                        fabricSees++;
                        if (Proxy.isProxyClass(storage.getClass())
                                && Proxy.getInvocationHandler(storage) instanceof StorageHandler) {
                            onlyThroughBridge++;
                        }
                    }
                }
            }
        }
        LOGGER.info("[NeoFobric] transfer sweep around the player: {} NeoForge inventories, {} reachable from Fabric "
                + "({} of them only because of this bridge)", neoInventories, fabricSees, onlyThroughBridge);
        if (fabricSees < neoInventories) {
            failures.add("sweep: " + (neoInventories - fabricSees) + " of " + neoInventories
                    + " NeoForge inventories are invisible to Fabric's lookup");
        }
    }

    private static void report(List<String> failures) {
        if (failures.isEmpty()) {
            LOGGER.info("[NeoFobric] transfer probe: PASS (commit, abort, nested abort, simulate and extract all agree with the NeoForge handler)");
        } else {
            for (var failure : failures) {
                LOGGER.error("[NeoFobric] transfer probe: FAIL {}", failure);
            }
        }
    }

    private static int count(Object container, int slot) throws Exception {
        var stack = (Object) container.getClass().getMethod("getItem", int.class).invoke(container, slot);
        if (stack == null) {
            return 0;
        }
        return (int) stack.getClass().getMethod("getCount").invoke(stack);
    }

    private static Object itemStack(String itemName, int size) throws Exception {
        return load("net.minecraft.world.item.ItemStack").getConstructor(load("net.minecraft.world.level.ItemLike"), int.class)
                .newInstance(item(itemName), size);
    }

    private static Object itemVariant(Object stack) throws Exception {
        return ITEMS.faVariant.getMethod("of", load("net.minecraft.world.item.ItemStack")).invoke(null, stack);
    }

    private static Object item(String itemName) throws Exception {
        var identifier = load("net.minecraft.resources.Identifier").getMethod("parse", String.class)
                .invoke(null, itemName);
        var registry = load("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
        var found = registry.getClass().getMethod("get", identifier.getClass()).invoke(registry, identifier);
        if (found instanceof java.util.Optional<?> optional) {
            found = optional.orElse(null);
        }
        return found.getClass().getMethod("value").invoke(found);
    }
}
