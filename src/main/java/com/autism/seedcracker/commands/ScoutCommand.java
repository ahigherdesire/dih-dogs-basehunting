package com.autism.seedcracker.commands;

import dihclient.commands.DihCommandSource;
import dihclient.commands.Command;
import dihclient.util.DihClientMessaging;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.compat.ModuleLookup;
import com.autism.seedcracker.finder.ScoutJournal;
import com.autism.seedcracker.modules.BaseScoutModule;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import java.io.IOException;

public final class ScoutCommand extends Command {
    public ScoutCommand() { super("scout","Guarded scouting: start, stop, status, load 1..128, review session candidate label."); }
    private static BaseScoutModule scout() {
        var module=ModuleLookup.get(SeedcrackerAddon.ID+":base-scout");
        if(!(module instanceof BaseScoutModule scout)) throw new IllegalStateException("Base Scout not registered");
        return scout;
    }
    @Override public void build(LiteralArgumentBuilder<DihCommandSource> root) {
        root.executes(context->status());
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("status").executes(context->status()));
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("stop").executes(context->{scout().setEnabled(false); return status();}));
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("start").executes(context->{var module=scout(); if(module.isEnabled()) module.setEnabled(false); module.setEnabled(true); return status();}));
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("load").then(RequiredArgumentBuilder.<DihCommandSource,Integer>argument("chunks",IntegerArgumentType.integer(1,128))
            .executes(context->{scout().setValue("scan-load",Integer.toString(IntegerArgumentType.getInteger(context,"chunks"))); return status();})));
        root.then(LiteralArgumentBuilder.<DihCommandSource>literal("review")
            .then(RequiredArgumentBuilder.<DihCommandSource,String>argument("session",StringArgumentType.word())
            .then(RequiredArgumentBuilder.<DihCommandSource,String>argument("candidate",StringArgumentType.word())
            .then(RequiredArgumentBuilder.<DihCommandSource,String>argument("label",StringArgumentType.word()).executes(context->{
                try {
                    if(scout().isEnabled()) throw new IllegalArgumentException("Stop scouting before reviewing evidence");
                    ScoutJournal.review(BaseScoutModule.evidenceDirectory(),StringArgumentType.getString(context,"session"),StringArgumentType.getString(context,"candidate"),StringArgumentType.getString(context,"label"));
                    DihClientMessaging.sendPrefixed("[Scout] Review saved. Labels are manual evidence, not detector certainty.");
                } catch(IOException|IllegalArgumentException error) { DihClientMessaging.sendPrefixed("[Scout] Review rejected: "+error.getMessage()); }
                return SUCCESS;
            })))));
    }
    private static int status() {
        var module=scout(); DihClientMessaging.sendPrefixed("[Scout] "+module.info()+" / session "+module.sessionId());
        return SUCCESS;
    }
}