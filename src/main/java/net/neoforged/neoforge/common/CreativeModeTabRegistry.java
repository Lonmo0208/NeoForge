/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.common;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.graph.ElementOrder;
import com.google.common.graph.GraphBuilder;
import com.google.common.graph.MutableGraph;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.fml.loading.toposort.TopologicalSort;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class CreativeModeTabRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Identifier CREATIVE_MODE_TAB_ORDERING_JSON = Identifier.fromNamespaceAndPath("neoforge", "creative_mode_tab_ordering.json");
    private static final List<CreativeModeTab> SORTED_TABS = new ArrayList<>();
    private static final List<CreativeModeTab> SORTED_TABS_VIEW = Collections.unmodifiableList(SORTED_TABS);
    private static final List<CreativeModeTab> DEFAULT_TABS = new ArrayList<>();

    /**
     * {@return an unmodifiable view of the sorted list of creative mode tabs in ascending order}
     */
    public static List<CreativeModeTab> getSortedCreativeModeTabs() {
        return SORTED_TABS_VIEW;
    }

    public static List<CreativeModeTab> getDefaultTabs() {
        return Collections.unmodifiableList(DEFAULT_TABS);
    }

    /**
     * {@return the {@linkplain CreativeModeTab} with the given name, or null}
     *
     * @param name the name to look up
     */
    @Nullable
    public static CreativeModeTab getTab(Identifier name) {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getValue(name);
    }

    /**
     * {@return the name of the given {@linkplain CreativeModeTab}, or null}
     *
     * @param tab the tab to look up
     */
    @Nullable
    public static Identifier getName(CreativeModeTab tab) {
        return BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
    }

    private static final Multimap<Identifier, Identifier> edges = HashMultimap.create();

    static PreparableReloadListener getReloadListener() {
        return new SimplePreparableReloadListener<JsonObject>() {
            final Gson gson = new GsonBuilder().create();

            @Override
            protected JsonObject prepare(ResourceManager resourceManager, ProfilerFiller profiler) {
                Optional<Resource> res = resourceManager.getResource(CREATIVE_MODE_TAB_ORDERING_JSON);
                if (res.isEmpty())
                    return new JsonObject();

                try (Reader reader = res.get().openAsReader()) {
                    return this.gson.fromJson(reader, JsonObject.class);
                } catch (IOException e) {
                    LOGGER.error("Could not read CreativeModeTab sorting file " + CREATIVE_MODE_TAB_ORDERING_JSON, e);
                    return new JsonObject();
                }
            }

            @Override
            protected void apply(JsonObject data, ResourceManager resourceManager, ProfilerFiller p) {
                try {
                    if (data.size() > 0) {
                        JsonArray order = GsonHelper.getAsJsonArray(data, "order");
                        List<CreativeModeTab> customOrder = new ArrayList<>();
                        for (JsonElement entry : order) {
                            Identifier id = Identifier.parse(entry.getAsString());
                            CreativeModeTab CreativeModeTab = getTab(id);
                            if (CreativeModeTab == null)
                                throw new IllegalStateException("CreativeModeTab not found with name " + id);
                            customOrder.add(CreativeModeTab);
                        }

                        List<CreativeModeTab> missingCreativeModeTabs = BuiltInRegistries.CREATIVE_MODE_TAB.stream().filter(CreativeModeTab -> !customOrder.contains(CreativeModeTab)).toList();
                        if (!missingCreativeModeTabs.isEmpty())
                            throw new IllegalStateException("CreativeModeTabs missing from the ordered list: " + missingCreativeModeTabs.stream()
                                    .map(CreativeModeTab -> Objects.toString(CreativeModeTabRegistry.getName(CreativeModeTab))).collect(Collectors.joining(", ")));

                        setCreativeModeTabOrder(customOrder);
                        return;
                    }
                } catch (Exception e) {
                    LOGGER.error("Error parsing CreativeModeTab sorting file " + CREATIVE_MODE_TAB_ORDERING_JSON, e);
                }

                recalculateItemCreativeModeTabs();
            }
        };
    }

    @SuppressWarnings("UnstableApiUsage")
    private static void recalculateItemCreativeModeTabs() {
        // A tab registered after the last ordering pass has no edges; without this it would be placed
        // by the name comparator instead of after the vanilla tabs. See #applyDefaultTabOrdering.
        if (hasTabsWithoutOrder()) {
            applyDefaultTabOrdering();
        }

        final MutableGraph<CreativeModeTab> graph = GraphBuilder.directed().nodeOrder(ElementOrder.<CreativeModeTab>insertion()).build();

        for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
            if (!DEFAULT_TABS.contains(tab))
                graph.addNode(tab);
        }
        edges.forEach((key, value) -> {
            final CreativeModeTab keyC = getTab(key);
            final CreativeModeTab valueC = getTab(value);
            if (keyC != null && valueC != null)
                graph.putEdge(keyC, valueC);
        });
        //Note: We can ignore the null warning as we validate getting tabs by key before adding to the graph
        // so the graph should only contain tabs that we know have names in the registry
        List<CreativeModeTab> tierList = TopologicalSort.topologicalSort(graph, Comparator.comparing(tab -> getName(tab)));

        setCreativeModeTabOrder(tierList);
    }

    private static void setCreativeModeTabOrder(List<CreativeModeTab> tierList) {
        runInServerThreadIfPossible(hasServer -> {
            SORTED_TABS.clear();
            SORTED_TABS.addAll(tierList);
        });
    }

    private static void runInServerThreadIfPossible(BooleanConsumer runnable) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
            server.execute(() -> runnable.accept(true));
        else
            runnable.accept(false);
    }

    /**
     * The vanilla tabs that are not {@link #DEFAULT_TABS}, in the order they are shown.
     *
     * <p>Identified by key rather than by registry position on purpose: the old code assumed
     * {@code registryOrder[0..9]} were the vanilla tabs, which only holds while every mod registers
     * <em>after</em> vanilla content. A tab registered late (a bridged Fabric mod registers in its
     * entrypoint, after the registry events this class is sorted from) would shift that window and
     * scramble the whole vanilla order.
     */
    private static final List<ResourceKey<CreativeModeTab>> VANILLA_TAB_ORDER = List.of(
            CreativeModeTabs.BUILDING_BLOCKS,
            CreativeModeTabs.COLORED_BLOCKS,
            CreativeModeTabs.NATURAL_BLOCKS,
            CreativeModeTabs.FUNCTIONAL_BLOCKS,
            CreativeModeTabs.REDSTONE_BLOCKS,
            CreativeModeTabs.TOOLS_AND_UTILITIES,
            CreativeModeTabs.COMBAT,
            CreativeModeTabs.FOOD_AND_DRINKS,
            CreativeModeTabs.INGREDIENTS,
            CreativeModeTabs.SPAWN_EGGS);

    /**
     * Derives the ordering edges that do not come from tab declarations: the vanilla chain, and
     * "modded tabs come after the last vanilla tab" for everything else.
     *
     * <p>Called from {@link #sortTabs()} and again from {@link #recalculateItemCreativeModeTabs()}
     * whenever a tab appeared after the last ordering pass: with no edge at all, the topological sort
     * falls back to its name comparator, and e.g. {@code create:base} sorts before
     * {@code minecraft:building_blocks} - the modded tabs jump to the first page of the creative
     * screen and push vanilla to the second, which is exactly what bridged Fabric mods (their
     * entrypoints run after the registry events) produced.
     */
    private static void applyDefaultTabOrdering() {
        edges.clear();

        Identifier lastVanilla = null;
        for (final ResourceKey<CreativeModeTab> key : VANILLA_TAB_ORDER) {
            final CreativeModeTab tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(key);
            if (tab == null)
                continue;
            final Identifier name = key.identifier();

            if (!tab.tabsBefore.isEmpty() || !tab.tabsAfter.isEmpty()) {
                addTabOrder(tab, name);
            } else {
                // If there is no order specified ensure vanilla ordering by specifying the previous and next vanilla tab as edges
                if (lastVanilla != null)
                    edges.put(lastVanilla, name);
                lastVanilla = name;
            }
        }

        for (final Holder<CreativeModeTab> value : BuiltInRegistries.CREATIVE_MODE_TAB.listElements().toList()) {
            if (DEFAULT_TABS.contains(value.value()))
                continue;
            if (VANILLA_TAB_ORDER.contains(value.unwrapKey().orElseThrow()))
                continue;

            final CreativeModeTab tab = value.value();
            final Identifier name = value.unwrapKey().orElseThrow().identifier();

            if (!tab.tabsBefore.isEmpty() || !tab.tabsAfter.isEmpty())
                addTabOrder(tab, name);
            else if (lastVanilla != null) // if there is no order specified ensure the tab comes after the last vanilla tab
                edges.put(lastVanilla, name);
        }
    }

    /**
     * {@return whether the registry contains a tab that has no ordering at all - neither declared by
     * the tab nor derived by {@link #applyDefaultTabOrdering()}} Such a tab would be placed by the
     * name comparator instead of after the vanilla tabs.
     */
    private static boolean hasTabsWithoutOrder() {
        for (final Holder<CreativeModeTab> value : BuiltInRegistries.CREATIVE_MODE_TAB.listElements().toList()) {
            final CreativeModeTab tab = value.value();
            if (DEFAULT_TABS.contains(tab) || !tab.tabsBefore.isEmpty() || !tab.tabsAfter.isEmpty())
                continue;
            final Identifier name = value.unwrapKey().orElseThrow().identifier();
            if (!edges.containsKey(name) && !edges.containsValue(name))
                return true;
        }
        return false;
    }

    @ApiStatus.Internal
    public static void sortTabs() {
        DEFAULT_TABS.clear();
        DEFAULT_TABS.add(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeModeTabs.HOTBAR));
        DEFAULT_TABS.add(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeModeTabs.SEARCH));
        DEFAULT_TABS.add(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeModeTabs.OP_BLOCKS));
        DEFAULT_TABS.add(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(CreativeModeTabs.INVENTORY));

        applyDefaultTabOrdering();
        recalculateItemCreativeModeTabs();
    }

    private static void addTabOrder(CreativeModeTab tab, Identifier name) {
        for (final Identifier after : tab.tabsAfter) {
            edges.put(name, after);
        }

        for (final Identifier before : tab.tabsBefore) {
            edges.put(before, name);
        }
    }
}
