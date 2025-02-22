# FabricTPA
[![CurseForge downloads](http://cf.way2muchnoise.eu/short_423295.svg)](https://www.curseforge.com/minecraft/mc-mods/fabrictpa)
[![GitHub package.json version](https://img.shields.io/github/v/release/CodedSakura/FabricTPA)](https://github.com/CodedSakura/FabricTPA)
[![Discord server](https://img.shields.io/discord/805088174085767219)](https://discord.gg/BkKG6nx6rG)  
A server-side Fabric/Quilt mod that adds /tpa command-set.  
Works for Minecraft 1.16.2+ (snapshots not fully tested)  
Requires [FabricAPI](https://www.curseforge.com/minecraft/mc-mods/fabric-api)  

## Commands
All commands except `/tpaconfig` don't require OP permissions. They're meant for everyone on a server to use.

`/tpa <player>` - Initiates request for you to teleport to `<player>`  
`/tpahere <player>` - Initiates request for `<player>` to teleport to you

`/tpacancel [<player>]` - Cancel a tpa or tpahere request you've initiated, argument required if multiple ongoing  
`/tpaaccept [<player>]` - Accept a tpa or tpahere request you've received, argument required if multiple ongoing  
`/tpadeny [<player>]` - Deny a tpa or tpahere request you've received, argument required if multiple ongoing
`/back` - Return to previous position  

`/tpaconfig [<option> [<value>]]` - Get/Set config options, more info below (Requres OP permission level 2)

## LuckPers / fabric-permission-api
There are only 2 permissions:
- `fabrictpa.tpa` allows players to execute the base commands
- `fabrictpa.config` allows players to edit the config using the `/tpaconfig` command

## Configuration
Configuration is done through `/tpaconfig`. There are currently 5 configurable options.  
Configuration is also saved in `config/FabricTPA.properties`, from which the values are loaded at server startup.
It also updates when a setting is changed in-game.

`timeout` - How long should it take for a tpa or tpahere request to time out, if not accepted/denied/cancelled. Default: 60 (seconds)  
`stand-still` - How long should the player stand still for after accepting a tpa or tpahere request. Default: 5 (seconds)  
`disable-bossbar` - Whether to disable the boss bar indication for standing still, if set to true will use action bar for time. Default: false  
`cooldown` - The minimum time between teleporting and the next request. Default: 5 (seconds)  
`cooldown-mode` - The mode for the cooldown, one of 3 values: `WhoTeleported`, `WhoInitiated`, `BothUsers`. Default: `WhoTeleported`. More info below  

## Cooldown modes

`WhoTeleported` - The cooldown is applied to whoever got teleported  
`WhoInitiated` - Cooldown is applied to whoever initiated the request  
`BothUsers` - The Cooldown is applied to both players involved in the teleport request
