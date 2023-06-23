package eu.codedsakura.fabrictpa;

import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import eu.codedsakura.mods.ConfigUtils;
import eu.codedsakura.mods.TeleportUtils;
import eu.codedsakura.mods.callback.DieRegistrationCallback;
import eu.codedsakura.mods.fpapiutils.FPAPIUtilsWrapper;
import eu.codedsakura.mods.mixin.DieRegistrationMixin;
import eu.codedsakura.mods.mixin.ServerPlayerMixin;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.minecraft.command.argument.EntityArgumentType.getPlayer;
import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class FabricTPA implements ModInitializer {
    private static final Logger logger = LogManager.getLogger("FabricTPA");
    public static final String QUICK_BACK = "quickback";
    private static final String CONFIG_NAME = "FabricTPA.properties";

    private final ArrayList<TPARequest> activeTPA = new ArrayList<>();
    private final HashMap<UUID, Long> recentRequests = new HashMap<>();
    private ConfigUtils config;

    @Nullable
    private static CompletableFuture<Suggestions> filterSuggestionsByInput(SuggestionsBuilder builder,
                                                                           List<String> values) {
        String start = builder.getRemaining().toLowerCase();
        values.stream().filter(s -> s.toLowerCase().startsWith(start)).forEach(builder::suggest);
        return builder.buildFuture();
    }

    private CompletableFuture<Suggestions> getTPAInitSuggestions(CommandContext<ServerCommandSource> context,
                                                                 SuggestionsBuilder builder) {
        ServerCommandSource scs = context.getSource();

        List<String> activeTargets = Stream.concat(
                activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getEntityName()),
                activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getEntityName())).toList();
        List<String> others = Arrays.stream(scs.getServer().getPlayerNames())
                .filter(s -> !s.equals(scs.getName()) && !activeTargets.contains(s))
                .collect(Collectors.toList());
        return filterSuggestionsByInput(builder, others);
    }

    private CompletableFuture<Suggestions> getTPATargetSuggestions(CommandContext<ServerCommandSource> context,
                                                                   SuggestionsBuilder builder) {
        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rFrom.getEntityName())
                .collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    private CompletableFuture<Suggestions> getTPASenderSuggestions(CommandContext<ServerCommandSource> context,
                                                                   SuggestionsBuilder builder) {
        List<String> activeTargets = activeTPA.stream().map(tpaRequest -> tpaRequest.rTo.getEntityName())
                .collect(Collectors.toList());
        return filterSuggestionsByInput(builder, activeTargets);
    }

    private CompletableFuture<Suggestions> getHomeSuggestions(CommandContext<ServerCommandSource> context,
                                                              SuggestionsBuilder builder) {
        IStoreHome player = (IStoreHome) context.getSource().getPlayer();
        return filterSuggestionsByInput(builder, player == null ? new ArrayList<>() : player.getHomeNames());
    }

    static class CooldownModeConfigValue extends ConfigUtils.IConfigValue<TPACooldownMode> {
        public CooldownModeConfigValue(@NotNull String name, TPACooldownMode defaultValue,
                                       @Nullable ConfigUtils.Command command) {
            super(name, defaultValue, null, command, (context, builder) -> {
                List<String> tcmValues = Arrays.stream(TPACooldownMode.values()).map(String::valueOf)
                        .collect(Collectors.toList());
                return filterSuggestionsByInput(builder, tcmValues);
            });
        }

        @Override
        public TPACooldownMode getFromProps(Properties props) {
            return TPACooldownMode.valueOf(props.getProperty(name));
        }

        @Override
        public ArgumentType<?> getArgumentType() {
            return StringArgumentType.string();
        }

        @Override
        public TPACooldownMode parseArgumentValue(CommandContext<ServerCommandSource> ctx) {
            return TPACooldownMode.valueOf(StringArgumentType.getString(ctx, name));
        }
    }

    @Override
    public void onInitialize() {
        logger.info("Initializing...");

        config = new ConfigUtils(FabricLoader.getInstance().getConfigDir().resolve(CONFIG_NAME).toFile(), logger,
                Arrays.asList(new ConfigUtils.IConfigValue[]{
                        new ConfigUtils.IntegerConfigValue("timeout", 60,
                                new ConfigUtils.IntegerConfigValue.IntLimits(0),
                                new ConfigUtils.Command("Timeout is %s seconds", "Timeout set to %s seconds")),
                        new ConfigUtils.IntegerConfigValue("stand-still", 5,
                                new ConfigUtils.IntegerConfigValue.IntLimits(0),
                                new ConfigUtils.Command("Stand-Still time is %s seconds",
                                        "Stand-Still time set to %s seconds")),
                        new ConfigUtils.IntegerConfigValue("cooldown", 5,
                                new ConfigUtils.IntegerConfigValue.IntLimits(0),
                                new ConfigUtils.Command("Cooldown is %s seconds", "Cooldown set to %s seconds")),
                        new ConfigUtils.BooleanConfigValue("bossbar", true,
                                new ConfigUtils.Command("Boss-Bar on: %s", "Boss-Bar is now: %s")),
                        new CooldownModeConfigValue("cooldown-mode", TPACooldownMode.WhoTeleported,
                                new ConfigUtils.Command("Cooldown Mode is %s", "Cooldown Mode set to %s"))
                }));

        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> {
            dispatcher.register(literal("tpa")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> tpaInit(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpahere")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPAInitSuggestions)
                            .executes(ctx -> tpaHere(ctx, getPlayer(ctx, "target")))));

            dispatcher.register(literal("tpaaccept")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> tpaAccept(ctx.getSource().getPlayer(), getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaAccept(ctx.getSource().getPlayer(), null)));

            dispatcher.register(literal("tpadeny")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPATargetSuggestions)
                            .executes(ctx -> tpaDeny(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaDeny(ctx, null)));

            dispatcher.register(literal("tpacancel")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", EntityArgumentType.player()).suggests(this::getTPASenderSuggestions)
                            .executes(ctx -> tpaCancel(ctx, getPlayer(ctx, "target"))))
                    .executes(ctx -> tpaCancel(ctx, null)));
            dispatcher.register(literal("back")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .executes(this::tpaBack));
            dispatcher.register(config.generateCommand("tpaconfig", FPAPIUtilsWrapper.require("fabrictpa.config", 2)));

            dispatcher.register(literal("sethome")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", StringArgumentType.string()).suggests(this::getHomeSuggestions)
                            .executes(this::setHome))
                    .executes(this::setHome));
            dispatcher.register(literal("home")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", StringArgumentType.string()).suggests(this::getHomeSuggestions)
                            .executes(this::home))
                    .executes(this::home));
            dispatcher.register(literal("delhome")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .then(argument("target", StringArgumentType.string()).suggests(this::getHomeSuggestions)
                            .executes(this::delHome))
                    .executes(this::delHome));
            dispatcher.register(literal("tpahelp")
                    .requires(FPAPIUtilsWrapper.require("fabrictpa.tpa", true))
                    .executes(this::tpaHelp));
        });

        DieRegistrationCallback.EVENT.register(this::playerDie);

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            handler.player.sendMessage(Text.literal("输入指令</tpahelp>可查看便利指令说明！").formatted(Formatting.GREEN), false);
        });

    }

    public int tpaInit(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tTo) throws CommandSyntaxException {
        final ServerPlayerEntity tFrom = ctx.getSource().getPlayer();

        if (tFrom != null && tFrom.equals(tTo)) {
            tFrom.sendMessage(Text.literal("你不能请求传送到你自己位置!").formatted(Formatting.RED), false);
            return 1;
        }

        if (tFrom == null || checkCooldown(tFrom))
            return 1;

        TPARequest tr = new TPARequest(tFrom, tTo, false, (int) config.getValue("timeout") * 1000);
        if ("carpet.patches.EntityPlayerMPFake".equals(tTo.getClass().getName())) {
            activeTPA.add(tr);
            tpaAccept(tTo, ctx.getSource().getPlayer());
            return 1;
        }
        if (activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tFrom.sendMessage(Text.literal("已有一个正在进行的请求!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            activeTPA.remove(tr);
            tFrom.sendMessage(Text.literal("你传送到[" + tTo.getEntityName() + "]的请求已经超时!").formatted(Formatting.RED),
                    false);
            tTo.sendMessage(Text.literal("[" + tFrom.getEntityName() + "] 的传送请求已超时!").formatted(Formatting.RED), false);
        });
        activeTPA.add(tr);

        tFrom.sendMessage(
                Text.literal("你请求传送到 ").formatted(Formatting.LIGHT_PURPLE)
                        .append(Text.literal(tTo.getEntityName()).formatted(Formatting.AQUA))
                        .append(Text.literal("\n取消输入指令 ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpacancel [<player>]")
                                .styled(s -> s
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                "/tpacancel " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("/tpacancel " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\n此请求将在 " + config.getValue("timeout") + " 秒后超时.")
                                .formatted(Formatting.LIGHT_PURPLE)),
                false);

        tTo.sendMessage(
                Text.literal(tFrom.getEntityName()).formatted(Formatting.AQUA)
                        .append(Text.literal(" 请求传送到你的位置!").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("\n接受指令 ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpaaccept [<player>]")
                                .styled(s -> s
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                "/tpaaccept " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("/tpaaccept " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\n拒绝指令 ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpadeny [<player>]")
                                .styled(s -> s
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                "/tpadeny " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("/tpadeny " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\n此请求将在 " + config.getValue("timeout") + " 秒后超时.")
                                .formatted(Formatting.LIGHT_PURPLE)),
                false);
        return 1;
    }

    public void playerDie(ServerPlayerEntity player) {
        ((IStoreHome) player).setOldWorldCoordinate(new WorldCoordinate(player.getServerWorld(), player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch()));
    }

    public int tpaHere(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity tFrom)
            throws CommandSyntaxException {
        final ServerPlayerEntity tTo = ctx.getSource().getPlayer();

        if (tTo != null && tTo.equals(tFrom)) {
            tTo.sendMessage(Text.literal("你不能请求传送到你自己位置!").formatted(Formatting.RED), false);
            return 1;
        }

        if (tTo == null || tFrom == null || checkCooldown(tFrom))
            return 1;

        TPARequest tr = new TPARequest(tFrom, tTo, true, (int) config.getValue("timeout") * 1000);
        if (activeTPA.stream().anyMatch(tpaRequest -> tpaRequest.equals(tr))) {
            tTo.sendMessage(Text.literal("已有一个正在进行的请求!").formatted(Formatting.RED), false);
            return 1;
        }
        tr.setTimeoutCallback(() -> {
            activeTPA.remove(tr);
            tTo.sendMessage(Text.literal("你对 " + tFrom.getEntityName() + " 的传送请求已超时!").formatted(Formatting.RED),
                    false);
            tFrom.sendMessage(Text.literal("将您传送到 " + tTo.getEntityName() + " 的请求已超时!").formatted(Formatting.RED),
                    false);
        });
        activeTPA.add(tr);

        tTo.sendMessage(
                Text.literal("你已经请求 ").formatted(Formatting.LIGHT_PURPLE)
                        .append(Text.literal(tFrom.getEntityName()).formatted(Formatting.AQUA))
                        .append(Text.literal(" 传送到你身边!\n取消指令 ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpacancel [<player>]")
                                .styled(s -> s
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                "/tpacancel " + tFrom.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("/tpacancel " + tFrom.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\n此请求将在 " + config.getValue("timeout") + " 秒后超时.")
                                .formatted(Formatting.LIGHT_PURPLE)),
                false);

        tFrom.sendMessage(
                Text.literal(tTo.getEntityName()).formatted(Formatting.AQUA)
                        .append(Text.literal(" 要求你传送到他那里!").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("\n接受指令 ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpaaccept [<player>]")
                                .styled(s -> s
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                "/tpaaccept " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("/tpaaccept " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\n拒绝指令 ").formatted(Formatting.LIGHT_PURPLE))
                        .append(Text.literal("/tpadeny [<player>]")
                                .styled(s -> s
                                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                                "/tpadeny " + tTo.getEntityName()))
                                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                Text.literal("/tpadeny " + tTo.getEntityName())))
                                        .withColor(Formatting.GOLD)))
                        .append(Text.literal("\n此请求将在 " + config.getValue("timeout") + " 秒后超时.")
                                .formatted(Formatting.LIGHT_PURPLE)),
                false);
        return 1;
    }

    private boolean checkCooldown(ServerPlayerEntity tFrom) {
        if (recentRequests.containsKey(tFrom.getUuid())) {
            long diff = Instant.now().getEpochSecond() - recentRequests.get(tFrom.getUuid());
            if (diff < (int) config.getValue("cooldown")) {
                tFrom.sendMessage(Text.literal("你不能在").append(String.valueOf((int) config.getValue("cooldown") - diff))
                        .append("秒内提出请求!").formatted(Formatting.RED), false);
                return true;
            }
        }
        return false;
    }

    private enum TPAAction {
        ACCEPT, DENY, CANCEL
    }

    private TPARequest getTPARequest(ServerPlayerEntity rFrom, ServerPlayerEntity rTo, TPAAction action) {
        Optional<TPARequest> otr = activeTPA.stream()
                .filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom) && tpaRequest.rTo.equals(rTo)).findFirst();

        if (otr.isEmpty()) {
            if (action == TPAAction.CANCEL) {
                rFrom.sendMessage(Text.literal("没有正在进行的请求!").formatted(Formatting.RED), false);
            } else {
                rTo.sendMessage(Text.literal("没有正在进行的请求!").formatted(Formatting.RED), false);
            }
            return null;
        }

        return otr.get();
    }

    public int tpaAccept(ServerPlayerEntity rTo, ServerPlayerEntity rFrom)
            throws CommandSyntaxException {
        if (rTo == null) return 1;
        if (rFrom == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = Text.literal("您当前有多个活动的传送请求!请指定接受谁的请求.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getEntityName())
                        .forEach(name -> text.append(Text.literal(name).styled(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaaccept " + name))
                                .withHoverEvent(
                                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpaaccept " + name)))
                                .withColor(Formatting.GOLD))).append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(Text.literal("你目前没有任何传送请求!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo, TPAAction.ACCEPT);
        if (tr == null)
            return 1;
        TeleportUtils.genericTeleport((boolean) config.getValue("bossbar"), (int) config.getValue("stand-still"), rFrom,
                () -> {
                    if (tr.tFrom.isRemoved() || tr.tTo.isRemoved())
                        tr.refreshPlayers();
                    ((IStoreHome) tr.tFrom).setOldWorldCoordinate(new WorldCoordinate(tr.tFrom.getServerWorld(), tr.tFrom.getX(), tr.tFrom.getY(),
                            tr.tFrom.getZ(),
                            tr.tFrom.getYaw(), tr.tFrom.getPitch()));
                    tr.tFrom.teleport(tr.tTo.getServerWorld(), tr.tTo.getX(), tr.tTo.getY(), tr.tTo.getZ(),
                            tr.tTo.getYaw(), tr.tTo.getPitch());
                    switch ((TPACooldownMode) config.getValue("cooldown-mode")) {
                        case BothUsers -> {
                            recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
                            recentRequests.put(tr.tTo.getUuid(), Instant.now().getEpochSecond());
                        }
                        case WhoInitiated -> recentRequests.put(tr.rFrom.getUuid(), Instant.now().getEpochSecond());
                        case WhoTeleported -> recentRequests.put(tr.tFrom.getUuid(), Instant.now().getEpochSecond());
                    }
                });

        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(Text.literal("您已接受传送请求!"), false);
        if ("carpet.patches.EntityPlayerMPFake".equals(tr.rTo.getClass().getName())) {
            tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                    .append(Text.literal(" 地毯假人自动接受了传送请求!").formatted(Formatting.LIGHT_PURPLE)), false);
        } else {
            tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                    .append(Text.literal(" 已经接受了传送请求!").formatted(Formatting.LIGHT_PURPLE)), false);
        }
        return 1;
    }

    public int tpaBack(CommandContext<ServerCommandSource> ctx) throws CommandSyntaxException {
        final ServerPlayerEntity rFrom = ctx.getSource().getPlayer();
        if (rFrom == null) return 1;
        WorldCoordinate worldCoordinate = ((IStoreHome) rFrom).getOldWorldCoordinate();
        if (worldCoordinate == null) {
            rFrom.sendMessage(Text.literal("你目前没有可以返回的传送坐标!").formatted(Formatting.RED), false);
            return 1;
        }
        WorldCoordinate oldCoordinate = new WorldCoordinate(rFrom.getServerWorld(), rFrom.getX(), rFrom.getY(), rFrom.getZ(), rFrom.getYaw(), rFrom.getPitch());
        rFrom.teleport(worldCoordinate.targetWorld, worldCoordinate.x, worldCoordinate.y, worldCoordinate.z,
                worldCoordinate.yaw, worldCoordinate.pitch);
        ((IStoreHome) rFrom).setOldWorldCoordinate(oldCoordinate);
        rFrom.sendMessage(Text.literal("已返回之前的位置!再次发送指令</back>可返回传送前位置!").formatted(Formatting.GREEN), false);
        return 1;
    }

    public int tpaDeny(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rFrom)
            throws CommandSyntaxException {
        final ServerPlayerEntity rTo = ctx.getSource().getPlayer();
        if (rTo == null) return 1;
        if (rFrom == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rTo.equals(rTo)).toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = Text.literal("您当前有多个活动的传送请求!请指定要拒绝谁的请求.\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rFrom.getEntityName())
                        .forEach(name -> text.append(Text.literal(name).styled(
                                        s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpadeny " + name))
                                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                                        Text.literal("/tpadeny " + name)))
                                                .withColor(Formatting.GOLD)))
                                .append(" "));
                rTo.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rTo.sendMessage(Text.literal("你目前没有任何传送请求!").formatted(Formatting.RED), false);
                return 1;
            }
            rFrom = candidates[0].rFrom;
        }

        TPARequest tr = getTPARequest(rFrom, rTo, TPAAction.DENY);
        if (tr == null)
            return 1;
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rTo.sendMessage(Text.literal("您已取消传送请求!"), false);
        tr.rFrom.sendMessage(Text.literal(tr.rTo.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" 取消了传送请求!").formatted(Formatting.RED)), false);
        return 1;
    }

    public int tpaCancel(CommandContext<ServerCommandSource> ctx, ServerPlayerEntity rTo)
            throws CommandSyntaxException {
        final ServerPlayerEntity rFrom = ctx.getSource().getPlayer();
        if (rFrom == null) return 1;
        if (rTo == null) {
            TPARequest[] candidates;
            candidates = activeTPA.stream().filter(tpaRequest -> tpaRequest.rFrom.equals(rFrom))
                    .toArray(TPARequest[]::new);
            if (candidates.length > 1) {
                MutableText text = Text.literal("您当前有多个活动的传送请求!请指定要取消的请求。\n").formatted(Formatting.LIGHT_PURPLE);
                Arrays.stream(candidates).map(tpaRequest -> tpaRequest.rTo.getEntityName())
                        .forEach(name -> text.append(Text.literal(name).styled(s -> s
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpacancel " + name))
                                .withHoverEvent(
                                        new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("/tpacancel " + name)))
                                .withColor(Formatting.GOLD))).append(" "));
                rFrom.sendMessage(text, false);
                return 1;
            }
            if (candidates.length < 1) {
                rFrom.sendMessage(Text.literal("你目前没有任何传送请求!").formatted(Formatting.RED), false);
                return 1;
            }
            rTo = candidates[0].rTo;
        }

        System.out.printf("%s -> %s\n", rFrom.getEntityName(), rTo.getEntityName());
        TPARequest tr = getTPARequest(rFrom, rTo, TPAAction.CANCEL);
        if (tr == null)
            return 1;
        tr.cancelTimeout();
        activeTPA.remove(tr);
        tr.rFrom.sendMessage(Text.literal("您已取消传送请求!").formatted(Formatting.RED), false);
        tr.rTo.sendMessage(Text.literal(tr.rFrom.getEntityName()).formatted(Formatting.AQUA)
                .append(Text.literal(" 取消了传送请求!").formatted(Formatting.RED)), false);
        return 1;
    }


    private int setHome(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            WorldCoordinate homeCoordinate = new WorldCoordinate(player.getServerWorld(), player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
            String homeName = getStringTarget(ctx);
            int result = ((IStoreHome) player).setHome(homeCoordinate, homeName);
            if (result == 0) {
                player.sendMessage(Text.literal("你不能设置超过3处家，可以使用[/dehome <name>]删除指定处家!").formatted(Formatting.RED), false);
            } else {
                player.sendMessage(Text.literal("你已设置此处为家[").append(homeName).append("]的位置!").formatted(Formatting.GREEN), false);
            }
        }
        return 1;
    }

    private int home(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            String homeName = getStringTarget(ctx);
            WorldCoordinate oldCoordinate = new WorldCoordinate(player.getServerWorld(), player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());
            WorldCoordinate worldCoordinate = ((IStoreHome) player).getHome(homeName);
            if (worldCoordinate != null) {
                player.teleport(worldCoordinate.targetWorld, worldCoordinate.x, worldCoordinate.y, worldCoordinate.z,
                        worldCoordinate.yaw, worldCoordinate.pitch);
                ((IStoreHome) player).setOldWorldCoordinate(oldCoordinate);
                player.sendMessage(Text.literal("欢迎回家！").formatted(Formatting.GREEN), false);
            } else {
                player.sendMessage(Text.literal("你没有设置你的家[").append(homeName).formatted(Formatting.RED).append("]!"), false);
            }

        }
        return 1;
    }

    private int delHome(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        String homeName = getStringTarget(ctx);
        if (player != null) {
            int result = ((IStoreHome) player).delHome(homeName);
            if (result == 1) {
                player.sendMessage(Text.literal("您已删除家[").append(homeName).append("]!").formatted(Formatting.GREEN), false);
            } else {
                player.sendMessage(Text.literal("你没有设置你的家[").append(homeName).formatted(Formatting.RED).append("]!"), false);
            }
        }
        return 1;
    }

    private String getStringTarget(CommandContext<ServerCommandSource> ctx) {
        try {
            String target = ctx.getArgument("target", String.class);
            return "".equals(target) ? "home" : target;
        } catch (Exception e) {
            return "home";
        }
    }

    private int tpaHelp(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) {
            List<String> helps = new ArrayList<>();
            helps.add("</tpa> 请求传送到他人身边(地毯假人可以直接传)。");
            helps.add("</tpahere> 请求他人传送到自己身边。");
            helps.add("</back> 可以返回传送前或死亡前的位置。");
            helps.add("</sethome> 最多设置3处为家，重复名称则覆盖，默认名称为[home]。");
            helps.add("</home> 快速回家，默认名称为[home]。");
            helps.add("</delhome> 删除此处家，默认名称为[home]。");
            for (String help : helps) {
                player.sendMessage(Text.literal(help), false);
            }
        }
        return 1;
    }

    enum TPACooldownMode {
        WhoTeleported, WhoInitiated, BothUsers
    }

    static class TPARequest {
        ServerPlayerEntity tFrom;
        ServerPlayerEntity tTo;

        ServerPlayerEntity rFrom;
        ServerPlayerEntity rTo;

        boolean tpaHere;
        long timeout;

        Timer timer;

        public TPARequest(ServerPlayerEntity tFrom, ServerPlayerEntity tTo, boolean tpaHere, int timeoutMS) {
            this.tFrom = tFrom;
            this.tTo = tTo;
            this.tpaHere = tpaHere;
            this.timeout = timeoutMS;
            this.rFrom = tpaHere ? tTo : tFrom;
            this.rTo = tpaHere ? tFrom : tTo;
        }

        void setTimeoutCallback(Timeout callback) {
            timer = new Timer();
            timer.schedule(new TimerTask() {
                @Override
                public void run() {
                    callback.onTimeout();
                }
            }, timeout);
        }

        void cancelTimeout() {
            if (timer != null) {
                timer.cancel();
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            TPARequest that = (TPARequest) o;
            return tFrom.equals(that.tFrom) &&
                    tTo.equals(that.tTo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tFrom, tTo);
        }

        @Override
        public String toString() {
            return "TPARequest{" + "tFrom=" + tFrom +
                    ", tTo=" + tTo +
                    ", rFrom=" + rFrom +
                    ", rTo=" + rTo +
                    ", tpaHere=" + tpaHere +
                    '}';
        }

        public void refreshPlayers() {
            this.tFrom = tFrom.server.getPlayerManager().getPlayer(tFrom.getUuid());
            this.tTo = tTo.server.getPlayerManager().getPlayer(tTo.getUuid());
            this.rFrom = this.tpaHere ? tTo : tFrom;
            this.rTo = this.tpaHere ? tFrom : tTo;
            assert tFrom != null && tTo != null;
        }
    }

    interface Timeout {
        void onTimeout();
    }
}
