package com.xj.landscape.launcher.ui.gamedetail;

import android.content.Context;
import app.revanced.extension.gamehub.BhSettingsExporter;
import com.xj.common.service.bean.GameDetailEntity;
import kotlin.jvm.functions.Function1;

public final class BhExportLambda implements Function1<Object, Object> {
    public final GameDetailSettingMenu menu;
    public final GameDetailEntity game;

    public BhExportLambda(GameDetailSettingMenu menu, GameDetailEntity game) {
        this.menu = menu;
        this.game = game;
    }

    @Override
    public Object invoke(Object p1) {
        Context ctx = menu.z();
        int id = game.getId();
        String gameId = id > 0 ? String.valueOf(id) : game.getLocalGameId();
        String gameName = game.getName();
        BhSettingsExporter.showExportDialog(ctx, gameId, gameName);
        return null;
    }
}
