/*
 * Copyright (c) PORTB 2025
 *
 * Licensed under GNU LGPL v3
 * https://www.gnu.org/licenses/lgpl-3.0.txt
 */

package portb.biggerstacks.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import portb.biggerstacks.BiggerStacks;
import portb.biggerstacks.Constants;
import portb.biggerstacks.util.ItemStackSizeHelper;
import portb.configlib.template.TemplateOptions;
import portb.configlib.xml.Condition;
import portb.configlib.xml.Operator;
import portb.configlib.xml.Property;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

public final class TemplateOverrideConfig
{
    public enum TargetType
    {
        ITEM,
        TAG
    }

    public enum StackCategory
    {
        NORMAL,
        POTION,
        ENCHANTED_BOOK
    }

    public record StackOverride(TargetType targetType, String target, StackCategory category)
    {
        public Condition toCondition()
        {
            return targetType == TargetType.TAG
                    ? new Condition(Property.TAGS, Operator.INCLUDES, target)
                    : new Condition(Property.ID, Operator.EQUALS, target);
        }

        public int resolveStackSize(TemplateOptions options)
        {
            return switch (category)
                    {
                        case NORMAL -> options.getNormalStackLimit();
                        case POTION -> options.getPotionStackLimit();
                        case ENCHANTED_BOOK -> options.getEnchBookLimit();
                    };
        }

        public int resolveStackSizeFromRules()
        {
            return StackSizeRules.resolvePreset(category);
        }
    }

    private record OverrideContainer(List<StackOverride> entries) {}
    private record LegacyEntry(String id, Integer stackSize) {}
    private record LegacyContainer(List<LegacyEntry> overrides) {}

    private static final Gson GSON = new GsonBuilder().create();
    private static final Path CONFIG_PATH = Constants.RULESET_FILE.getParent().resolve("biggerstacks-template-overrides.json");

    private static final String FILE_HEADER = String.join(System.lineSeparator(),
            "// 基本用法：",
            "//   - 每一条规则写成一个 {} 对象，放在下面 entries 数组里",
            "//   - 看不懂就照着示例往下复制，改 category / targetType / target 即可",
            "//   - 物品或者标签会使用下方所配置规则的堆叠数",
            "//",
            "// 字段说明：",
            "//   category:",
            "//     - NORMAL          普通物品（使用普通物品堆叠规则）",
            "//     - POTION          药水类（使用药水堆叠规则）",
            "//     - ENCHANTED_BOOK  附魔书类（使用附魔书堆叠规则）",
            "//",
            "//   targetType:",
            "//     - ITEM  单个物品",
            "//     - TAG   整个标签（不需要写 #）",
            ""
    );

    private static final String SAMPLE_COMMENTS = String.join(System.lineSeparator(),
            "    // 下面是示例规则（默认可留空或注释掉）：",
            "    // {\"category\": \"NORMAL\", \"targetType\": \"ITEM\", \"target\": \"minecraft:totem_of_undying\"},",
            "    // {\"category\": \"NORMAL\", \"targetType\": \"TAG\",  \"target\": \"forge:ingots/iron\"}"
    );

    private static final List<StackOverride> DEFAULT_OVERRIDES = List.of();

    private static List<StackOverride> overridesCache;
    private static final Map<ResourceLocation, StackCategory> ITEM_OVERRIDES = new HashMap<>();
    private static final Map<TagKey<Item>, StackCategory>     TAG_OVERRIDES  = new HashMap<>();

    private TemplateOverrideConfig()
    {
    }

    public static List<StackOverride> getOverrides()
    {
        ensureLoaded();
        return overridesCache;
    }

    public static void reload()
    {
        overridesCache = null;
        ensureLoaded();
    }

    public static void updateOverrides(List<StackOverride> newOverrides)
    {
        List<StackOverride> sanitized = sanitizeEntries(newOverrides, false);
        writeConfigFile(sanitized);
        overridesCache = Collections.unmodifiableList(sanitized);
        rebuildLookupTables();
    }

    private static void ensureLoaded()
    {
        if (overridesCache != null)
        {
            return;
        }

        overridesCache = Collections.unmodifiableList(readOverridesFromDisk());
        rebuildLookupTables();
    }

    private static void rebuildLookupTables()
    {
        ITEM_OVERRIDES.clear();
        TAG_OVERRIDES.clear();
        ItemStackSizeHelper.invalidateCache();

        for (StackOverride entry : overridesCache)
        {
            if (entry.target() == null || entry.target().isBlank())
            {
                continue;
            }

            try
            {
                ResourceLocation key = ResourceLocation.parse(entry.target());
                if (entry.targetType() == TargetType.TAG)
                {
                    TagKey<Item> tagKey = TagKey.create(Registries.ITEM, key);
                    TAG_OVERRIDES.put(tagKey, entry.category());
                }
                else
                {
                    ITEM_OVERRIDES.put(key, entry.category());
                }
            }
            catch (IllegalArgumentException e)
            {
                BiggerStacks.LOGGER.warn("Ignoring invalid override target '{}': {}", entry.target(), e.getMessage());
            }
        }
    }

    private static List<StackOverride> readOverridesFromDisk()
    {
        if (Files.notExists(CONFIG_PATH))
        {
            writeDefaults();
            return DEFAULT_OVERRIDES;
        }

        try
        {
            String json = Files.readString(CONFIG_PATH, StandardCharsets.UTF_8);
            String sanitizedJson = stripJsonComments(json);
            OverrideContainer container = GSON.fromJson(sanitizedJson, OverrideContainer.class);
            if (container != null && container.entries != null)
            {
                return sanitizeEntries(container.entries, true);
            }

            LegacyContainer legacy = GSON.fromJson(sanitizedJson, LegacyContainer.class);
            if (legacy != null && legacy.overrides != null)
            {
                List<StackOverride> converted = legacy.overrides.stream()
                        .filter(entry -> entry != null && entry.id() != null && !entry.id().isBlank())
                        .map(entry -> new StackOverride(TargetType.ITEM,
                                                         entry.id().toLowerCase(Locale.ROOT),
                                                         StackCategory.NORMAL))
                        .collect(Collectors.toCollection(ArrayList::new));

                if (!converted.isEmpty())
                {
                    return Collections.unmodifiableList(converted);
                }
            }

            return DEFAULT_OVERRIDES;
        }
        catch (IOException | JsonParseException e)
        {
            BiggerStacks.LOGGER.error("Failed to read BiggerStacks template overrides file, falling back to defaults.", e);
            return DEFAULT_OVERRIDES;
        }
    }

    private static List<StackOverride> sanitizeEntries(List<StackOverride> entries, boolean fallbackToDefault)
    {
        List<StackOverride> sanitized = entries.stream()
                .filter(entry -> entry != null && entry.target() != null && !entry.target().isBlank())
                .map(entry -> new StackOverride(entry.targetType(),
                                                 entry.target().toLowerCase(Locale.ROOT),
                                                 entry.category()))
                .collect(Collectors.toList());

        if (sanitized.isEmpty() && fallbackToDefault)
        {
            return DEFAULT_OVERRIDES;
        }

        return Collections.unmodifiableList(sanitized);
    }

    private static void writeDefaults()
    {
        writeConfigFile(DEFAULT_OVERRIDES);
    }

    private static void writeConfigFile(List<StackOverride> entries)
    {
        try
        {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH, StandardCharsets.UTF_8))
            {
                writer.write(FILE_HEADER);
                writer.write("{");
                writer.write(System.lineSeparator());
                writer.write("  \"entries\": [");
                writer.write(System.lineSeparator());

                if (entries.isEmpty())
                {
                    writer.write(SAMPLE_COMMENTS);
                    writer.write(System.lineSeparator());
                }
                else
                {
                    for (int i = 0; i < entries.size(); i++)
                    {
                        StackOverride entry = entries.get(i);
                        writer.write("    ");
                        writer.write(GSON.toJson(entry));
                        if (i < entries.size() - 1)
                        {
                            writer.write(",");
                        }
                        writer.write(System.lineSeparator());
                    }
                }

                writer.write("  ]");
                writer.write(System.lineSeparator());
                writer.write("}");
                writer.write(System.lineSeparator());
            }
        }
        catch (IOException e)
        {
            BiggerStacks.LOGGER.warn("Unable to write template overrides file: {}", e.getMessage());
        }
    }

    private static String stripJsonComments(String json)
    {
        StringBuilder builder = new StringBuilder();
        String[] lines = json.split("\\R");
        for (String line : lines)
        {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("//"))
            {
                continue;
            }
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    public static OptionalInt resolveOverride(ItemStack stack)
    {
        ensureLoaded();
        if (stack.isEmpty())
        {
            return OptionalInt.empty();
        }

        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id != null)
        {
            StackCategory category = ITEM_OVERRIDES.get(id);
            if (category != null)
            {
                return OptionalInt.of(StackSizeRules.resolvePreset(category));
            }
        }

        for (Map.Entry<TagKey<Item>, StackCategory> entry : TAG_OVERRIDES.entrySet())
        {
            if (stack.is(entry.getKey()))
            {
                return OptionalInt.of(StackSizeRules.resolvePreset(entry.getValue()));
            }
        }

        return OptionalInt.empty();
    }
}
